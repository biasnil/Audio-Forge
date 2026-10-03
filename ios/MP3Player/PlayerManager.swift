import Foundation
import Combine
import AVFoundation
import MediaPlayer
import UIKit

nonisolated enum SleepTimer: Hashable {
    case off
    case minutes(Int)
    case endOfTrack
}

/// What gets saved so the app can resume where you left off.
nonisolated private struct SavedPlayerState: Codable {
    var queueKeys: [String]
    var index: Int
    var time: TimeInterval
    var shuffleMode: ShuffleMode
    var repeatMode: RepeatMode
    var rate: Float
}

/// The state saved by older versions of the app (songs by file name, Documents only).
nonisolated private struct LegacyPlayerState: Codable {
    var queueFiles: [String]
    var index: Int
    var time: TimeInterval
    var shuffled: Bool
    var repeatMode: RepeatMode
    var rate: Float
}

/// Playback through AVAudioEngine, so it can crossfade, equalize and boost:
///
///     slot 0: player -> fader -> time pitch (speed) -> gain (ReplayGain) ─┐
///     slot 1: player -> fader -> time pitch (speed) -> gain (ReplayGain) ─┴> mix -> 10-band EQ -> output
///
/// Only the player -> fader link uses the song file's own format (the fader is a
/// mixer, which converts any sample rate / channel count). Everything after it runs
/// in one fixed format, because the effect units throw (-10868, format not
/// supported) when reconnected with arbitrary file formats.
///
/// One slot plays the current song; during a crossfade the other fades the
/// next song in and then becomes the current one (like the desktop/Android app).
/// The EQ's global gain carries the volume (up to 200%) and the EQ post-gain.
@MainActor
final class PlayerManager: ObservableObject {
    @Published private(set) var currentSong: Song?
    @Published private(set) var isPlaying = false
    /// The playback position lives in its own object (`clock`), so the many views that
    /// observe the player don't redraw 5 times a second.
    let clock = PlaybackClock()
    var currentTime: TimeInterval { clock.time }
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var shuffleMode: ShuffleMode = .off
    @Published private(set) var repeatMode: RepeatMode = .off
    @Published private(set) var rate: Float = 1.0
    @Published private(set) var volumePercent: Int
    @Published private(set) var sleepTimer: SleepTimer = .off
    @Published private(set) var sleepEndDate: Date?
    /// Set when a song can't be played; the UI shows it and clears it.
    @Published var errorMessage: String?

    /// One player node and its effects chain.
    private final class Slot {
        let player = AVAudioPlayerNode()
        let timePitch = AVAudioUnitTimePitch()
        let gain = AVAudioUnitEQ(numberOfBands: 1)
        let fader = AVAudioMixerNode()
        var connectedFormat: AVAudioFormat?
        var file: AVAudioFile?
        var song: Song?
        /// First frame of the segment that's scheduled now.
        var startFrame: AVAudioFramePosition = 0
        /// Position while not running.
        var pausedTime: TimeInterval = 0
        var running = false
        /// Bumped whenever the scheduled segment is replaced, so stale completions are ignored.
        var generation = 0

        var duration: TimeInterval {
            guard let file, file.processingFormat.sampleRate > 0 else { return song?.duration ?? 0 }
            return Double(file.length) / file.processingFormat.sampleRate
        }

        init() {
            gain.bands[0].bypass = true
        }
    }

    private let settings: SettingsStore
    /// The latest settings (@Published delivers them before `settings.settings` changes).
    private var audio: AppSettings
    private let engine = AVAudioEngine()
    private let mix = AVAudioMixerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: EqualizerConfig.bandCount)
    private let slots = [Slot(), Slot()]
    private var activeIndex = 0
    private var active: Slot { slots[activeIndex] }
    private var incoming: Slot { slots[1 - activeIndex] }
    private var crossfading = false
    /// The next song couldn't be loaded for a fade: don't retry on every tick.
    private var crossfadeBlocked = false

    private var queue = PlaybackQueue<Song>()
    private var wasPlayingBeforeInterruption = false
    private var sleepTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var tickCount = 0
    private var hasRestored = false
    private var cancellables = Set<AnyCancellable>()
    private var nowPlayingArtwork: (key: String, artwork: MPMediaItemArtwork?)?

    private static let stateKey = "savedPlayerState.v2"
    private static let legacyStateKey = "savedPlayerState"

    init(settings: SettingsStore) {
        self.settings = settings
        volumePercent = settings.settings.volumePercent
        audio = settings.settings
        do {
            // .playback keeps audio going when the phone is locked or on silent.
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        } catch {
            print("Audio session error: \(error)")
        }
        buildGraph()
        setupRemoteCommands()
        observeSessionEvents()

        settings.$settings
            .sink { [weak self] new in self?.applyAudioSettings(new) }
            .store(in: &cancellables)

        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }

    // MARK: - Audio graph

    private func buildGraph() {
        engine.attach(mix)
        engine.attach(eq)
        let format = processingFormat
        for (index, slot) in slots.enumerated() {
            engine.attach(slot.player)
            engine.attach(slot.fader)
            engine.attach(slot.timePitch)
            engine.attach(slot.gain)
            engine.connect(slot.player, to: slot.fader, format: format)
            slot.connectedFormat = format
            engine.connect(slot.fader, to: slot.timePitch, format: format)
            engine.connect(slot.timePitch, to: slot.gain, format: format)
            engine.connect(slot.gain, to: mix, fromBus: 0, toBus: AVAudioNodeBus(index), format: format)
        }
        engine.connect(mix, to: eq, format: format)
        engine.connect(eq, to: engine.mainMixerNode, format: format)

        for (band, frequency) in zip(eq.bands, EqualizerConfig.frequencies) {
            band.filterType = .parametric
            band.frequency = frequency
            band.bandwidth = EqualizerConfig.bandwidthOctaves
            band.gain = 0
            band.bypass = true
        }
        engine.prepare()
    }

    /// The fixed format everything after the faders runs in.
    private let processingFormat = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!

    /// Reconnects a slot's player to its fader in the song file's own format
    /// (the player must be stopped). The fader converts it to `processingFormat`.
    private func connectPlayer(_ slot: Slot, format: AVAudioFormat) {
        engine.disconnectNodeOutput(slot.player)
        engine.connect(slot.player, to: slot.fader, format: format)
        slot.connectedFormat = format
    }

    private func startEngineIfNeeded() -> Bool {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            if !engine.isRunning { try engine.start() }
            return true
        } catch {
            errorMessage = "Audio couldn't start: \(error.localizedDescription)"
            return false
        }
    }

    // MARK: - Slots

    /// Opens a song's file in `slot` (stopped, at the start). False if it can't be read.
    private func load(_ song: Song, into slot: Slot) -> Bool {
        stopSlot(slot)
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: song.url)
        } catch {
            slot.file = nil
            slot.song = nil
            return false
        }
        if slot.connectedFormat != file.processingFormat {
            connectPlayer(slot, format: file.processingFormat)
        }
        slot.file = file
        slot.song = song
        slot.startFrame = 0
        slot.pausedTime = 0
        slot.timePitch.rate = rate
        applyReplayGain(slot)
        return true
    }

    /// Starts `slot` playing from `time`.
    private func start(_ slot: Slot, at time: TimeInterval) {
        guard let file = slot.file, startEngineIfNeeded() else { return }
        stopSlot(slot)
        let sampleRate = file.processingFormat.sampleRate
        let startFrame = min(max(AVAudioFramePosition(time * sampleRate), 0), file.length)
        let frameCount = AVAudioFrameCount(max(file.length - startFrame, 0))
        slot.startFrame = startFrame
        slot.pausedTime = Double(startFrame) / sampleRate
        slot.generation += 1
        let generation = slot.generation
        let index = slots.firstIndex { $0 === slot } ?? 0

        guard frameCount > 0 else {
            slot.running = true
            Task { self.segmentFinished(slot: index, generation: generation) }
            return
        }
        slot.player.scheduleSegment(file, startingFrame: startFrame, frameCount: frameCount, at: nil,
                                    completionCallbackType: .dataPlayedBack,
                                    completionHandler: Self.completion(for: self, slot: index, generation: generation))
        slot.player.play()
        slot.running = true
    }

    /// Stops a slot, remembering where it was.
    private func stopSlot(_ slot: Slot) {
        if slot.running { slot.pausedTime = position(of: slot) }
        slot.generation += 1
        slot.player.stop()
        slot.running = false
    }

    private func position(of slot: Slot) -> TimeInterval {
        guard let file = slot.file else { return 0 }
        guard slot.running,
              let nodeTime = slot.player.lastRenderTime,
              let playerTime = slot.player.playerTime(forNodeTime: nodeTime) else { return slot.pausedTime }
        let time = Double(slot.startFrame + playerTime.sampleTime) / file.processingFormat.sampleRate
        return min(max(time, 0), slot.duration)
    }

    // Built outside the main actor: the engine calls it on its own thread.
    nonisolated private static func completion(for manager: PlayerManager, slot: Int, generation: Int)
        -> @Sendable (AVAudioPlayerNodeCompletionCallbackType) -> Void {
        { _ in
            Task { @MainActor in manager.segmentFinished(slot: slot, generation: generation) }
        }
    }

    private func segmentFinished(slot index: Int, generation: Int) {
        let slot = slots[index]
        guard slot.generation == generation, slot.running else { return }
        slot.running = false
        slot.pausedTime = slot.duration
        guard slot === active else { return }      // a (very short) song fading in ended: ignore
        if crossfading { finalizeCrossfade() } else { advance(automatic: true) }
    }

    // MARK: - Transport

    /// Replaces the queue with `songs` and plays the one at `index`.
    func play(_ songs: [Song], startAt index: Int) {
        guard songs.indices.contains(index) else { return }
        abortCrossfade()
        queue.setQueue(songs, startIndex: index)
        playCurrent()
    }

    /// Plays a list in smart shuffle (each song once), starting from a random song.
    func playShuffled(_ songs: [Song]) {
        guard !songs.isEmpty else { return }
        if queue.shuffleMode == .off { queue.setShuffleMode(.smart) }
        play(songs, startAt: Int.random(in: songs.indices))
    }

    /// Hard cut to the queue's current song.
    private func playCurrent(at time: TimeInterval = 0, autoplay: Bool = true) {
        guard let song = queue.currentItem else { return }
        crossfadeBlocked = false
        let slot = active
        slot.fader.outputVolume = 1
        guard load(song, into: slot) else {
            errorMessage = "Couldn't play \(song.fileName)."
            isPlaying = false
            engine.pause()
            publish()
            return
        }
        if autoplay {
            start(slot, at: time)
        } else {
            slot.pausedTime = min(time, slot.duration)
        }
        isPlaying = autoplay && slot.running
        publish()
        saveState()
    }

    func resume() {
        guard active.file != nil, !active.running else { return }
        start(active, at: active.pausedTime)
        isPlaying = active.running
        publish()
    }

    func pause() {
        abortCrossfade()
        stopSlot(active)
        isPlaying = false
        engine.pause()
        publish()
        saveState()
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    /// A manual skip always hard-cuts, dropping any fade in progress.
    func next() {
        abortCrossfade()
        queue.cancelPendingNext()
        advance(automatic: false)
    }

    func previous() {
        abortCrossfade()
        queue.cancelPendingNext()
        if position(of: active) > 3 {
            seek(to: 0)                          // restart the current song
        } else if queue.movePrevious() {
            playCurrent(autoplay: isPlaying || active.running)
        } else {
            seek(to: 0)
        }
    }

    /// automatic = the song finished by itself.
    private func advance(automatic: Bool) {
        guard !queue.isEmpty else { return }

        if automatic && sleepTimer == .endOfTrack {
            // Sleep timer "end of current song": just stop here.
            setSleepTimer(.off)
            stopAtEnd()
        } else if automatic && repeatMode == .one {
            start(active, at: 0)
        } else if queue.moveNext() {
            playCurrent(autoplay: automatic || isPlaying)
        } else {
            stopAtEnd()
        }
    }

    /// End of the queue: stop and rewind the last song.
    private func stopAtEnd() {
        stopSlot(active)
        active.pausedTime = 0
        isPlaying = false
        engine.pause()
        publish()
        saveState()
    }

    /// A seek during a crossfade drops the fade (the queue keeps its pick).
    func seek(to time: TimeInterval) {
        abortCrossfade()
        let target = min(max(time, 0), active.duration)
        if active.running {
            start(active, at: target)
        } else {
            active.pausedTime = target
        }
        clock.time = target
        updateNowPlaying()
    }

    // MARK: - Crossfade

    private func tick() {
        tickCount += 1
        guard active.file != nil else { return }
        let position = position(of: active)
        let length = active.duration

        if crossfading {
            updateCrossfade(position: position, length: length)
        } else if active.running, audio.crossfadeEnabled, !crossfadeBlocked {
            let fade = Double(audio.crossfadeSeconds)
            if length > fade * 2, (length - position) / Double(rate) <= fade {
                startCrossfade()
            }
        }
        if active.running, tickCount % 2 == 0, abs(clock.time - position) > 0.05 {
            clock.time = position                 // 0.2 s keeps synced lyrics responsive
        }
    }

    private func startCrossfade() {
        guard repeatMode != .one, queue.peekNext(), let next = queue.pendingNextItem else { return }
        let slot = incoming
        guard load(next, into: slot) else {
            // Gets another go (and reports the error) on the normal advance.
            crossfadeBlocked = true
            queue.cancelPendingNext()
            return
        }
        slot.fader.outputVolume = 0
        start(slot, at: 0)
        crossfading = true
    }

    private func updateCrossfade(position: TimeInterval, length: TimeInterval) {
        let remaining = (length - position) / Double(rate)
        let fade = Double(audio.crossfadeSeconds)
        let progress = Float(1 - min(max(remaining / fade, 0), 1))
        active.fader.outputVolume = 1 - progress
        incoming.fader.outputVolume = progress
        if remaining <= 0.05 { finalizeCrossfade() }
    }

    /// The faded-in slot becomes the active one.
    private func finalizeCrossfade() {
        guard crossfading else { return }
        crossfading = false
        let old = active
        activeIndex = 1 - activeIndex
        active.fader.outputVolume = 1
        stopSlot(old)
        old.file = nil
        old.song = nil
        queue.commitPendingNext()
        crossfadeBlocked = false
        isPlaying = active.running
        publish()
        saveState()
    }

    /// Drops a fade in progress, leaving the current song at full volume. Doesn't touch the queue.
    private func abortCrossfade() {
        guard crossfading else { return }
        crossfading = false
        stopSlot(incoming)
        incoming.file = nil
        incoming.song = nil
        active.fader.outputVolume = 1
    }

    // MARK: - Shuffle / Repeat / Speed / Volume

    func cycleShuffle() {
        queue.cycleShuffleMode()
        abortCrossfade()
        shuffleMode = queue.shuffleMode
        saveState()
    }

    func cycleRepeat() {
        queue.cycleRepeatMode()
        abortCrossfade()
        repeatMode = queue.repeatMode
        saveState()
    }

    func setRate(_ newRate: Float) {
        rate = newRate
        slots.forEach { $0.timePitch.rate = newRate }
        updateNowPlaying()
    }

    /// 0-200. Applies immediately; call `saveVolume` when the slider is let go.
    func setVolume(_ percent: Int) {
        volumePercent = min(max(percent, 0), AppSettings.maxVolumePercent)
        applyOutputGain(audio)
    }

    func saveVolume() {
        let percent = volumePercent
        settings.update { $0.volumePercent = percent }
    }

    private func applyAudioSettings(_ new: AppSettings) {
        audio = new
        for (band, gain) in zip(eq.bands, new.eqGainsDb) {
            band.gain = gain
            band.bypass = !new.eqEnabled
        }
        applyOutputGain(new)
        slots.forEach(applyReplayGain)
        if !new.crossfadeEnabled { abortCrossfade() }
    }

    /// Volume x EQ post-gain (if the EQ is on), as the EQ's global gain (limited to +24 dB).
    private func applyOutputGain(_ settings: AppSettings) {
        var db = linearToDb(Float(volumePercent) / 100)
        if settings.eqEnabled { db += settings.eqPostGainDb }
        eq.globalGain = min(max(db, -96), 24)
    }

    private func applyReplayGain(_ slot: Slot) {
        let db = audio.replayGainEnabled ? (slot.song?.replayGainDb ?? 0) : 0
        slot.gain.globalGain = min(max(db, -96), 24)
    }

    // MARK: - Sleep timer

    func setSleepTimer(_ timer: SleepTimer) {
        sleepTask?.cancel()
        sleepTask = nil
        sleepEndDate = nil
        sleepTimer = timer

        guard case .minutes(let minutes) = timer else { return }
        let seconds = TimeInterval(minutes * 60)
        sleepEndDate = Date().addingTimeInterval(seconds)

        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, let self else { return }
            self.pause()
            self.sleepTimer = .off
            self.sleepEndDate = nil
            self.sleepTask = nil
        }
    }

    // MARK: - Tag edits

    /// Where playback was when a file was taken away for a tag edit.
    struct FileEditHold {
        let time: TimeInterval
        let wasPlaying: Bool
    }

    /// Stops reading `song`'s file while it's rewritten. Returns a hold if it was the current song.
    func releaseFileForEdit(_ song: Song) -> FileEditHold? {
        if crossfading && incoming.song?.key == song.key {
            abortCrossfade()
            queue.cancelPendingNext()
        }
        guard active.song?.key == song.key else { return nil }
        abortCrossfade()
        let hold = FileEditHold(time: position(of: active), wasPlaying: active.running)
        stopSlot(active)
        active.file = nil
        return hold
    }

    /// Puts the edited song's new tags into the queue and, if it was current, reopens it where it was.
    func resumeAfterFileEdit(_ updated: Song, hold: FileEditHold?) {
        queue.updateItems { $0.key == updated.key ? updated : $0 }
        if nowPlayingArtwork?.key == updated.key { nowPlayingArtwork = nil }   // the cover may have changed
        if let hold {
            playCurrent(at: hold.time, autoplay: hold.wasPlaying)
        } else {
            publish()
        }
    }

    // MARK: - Resume where you left off

    func saveState() {
        guard !queue.isEmpty else {
            UserDefaults.standard.removeObject(forKey: Self.stateKey)
            return
        }
        let state = SavedPlayerState(
            queueKeys: queue.items.map(\.key),
            index: queue.currentIndex,
            time: position(of: active),
            shuffleMode: queue.shuffleMode,
            repeatMode: queue.repeatMode,
            rate: rate
        )
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: Self.stateKey)
        }
    }

    /// Loads the last song (paused, at the same position) once the library is ready.
    func restoreIfNeeded(from library: [Song]) {
        guard !hasRestored, !library.isEmpty else { return }
        hasRestored = true
        guard queue.isEmpty else { return }

        let defaults = UserDefaults.standard
        var state: SavedPlayerState?
        if let data = defaults.data(forKey: Self.stateKey) {
            state = try? JSONDecoder().decode(SavedPlayerState.self, from: data)
        } else if let data = defaults.data(forKey: Self.legacyStateKey),
                  let legacy = try? JSONDecoder().decode(LegacyPlayerState.self, from: data) {
            state = SavedPlayerState(queueKeys: legacy.queueFiles.map { "docs/\($0)" }, index: legacy.index,
                                     time: legacy.time, shuffleMode: legacy.shuffled ? .random : .off,
                                     repeatMode: legacy.repeatMode, rate: legacy.rate)
        }
        guard let state else { return }

        let byKey = Dictionary(library.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        let currentKey = state.queueKeys.indices.contains(state.index) ? state.queueKeys[state.index] : nil
        let songs = state.queueKeys.compactMap { byKey[$0] }
        guard !songs.isEmpty else { return }
        let found = currentKey.flatMap { key in songs.firstIndex { $0.key == key } }
        let index = found ?? 0

        queue.setShuffleMode(state.shuffleMode)
        queue.setRepeatMode(state.repeatMode)
        queue.setQueue(songs, startIndex: index)
        shuffleMode = queue.shuffleMode
        repeatMode = queue.repeatMode
        setRate(state.rate)
        playCurrent(at: found != nil ? state.time : 0, autoplay: false)
        clock.time = active.pausedTime
    }

    // MARK: - Interruptions (calls, alarms), headphones, route changes

    private func observeSessionEvents() {
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()

        center.addObserver(forName: AVAudioSession.interruptionNotification,
                           object: session, queue: .main) { [weak self] note in
            guard let self else { return }
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let options = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            MainActor.assumeIsolated {
                self.handleInterruption(typeValue: type, optionsValue: options)
            }
        }

        center.addObserver(forName: AVAudioSession.routeChangeNotification,
                           object: session, queue: .main) { [weak self] note in
            guard let self else { return }
            let reason = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            MainActor.assumeIsolated {
                self.handleRouteChange(reasonValue: reason)
            }
        }

        // The engine stops itself when the output changes (e.g. AirPods connect): restart it.
        center.addObserver(forName: .AVAudioEngineConfigurationChange,
                           object: engine, queue: .main) { [weak self] _ in
            guard let self else { return }
            MainActor.assumeIsolated {
                self.handleEngineConfigurationChange()
            }
        }
    }

    private func handleInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard let raw = typeValue,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }

        switch type {
        case .began:
            // iOS already stopped the engine (e.g. a phone call came in).
            wasPlayingBeforeInterruption = isPlaying
            if isPlaying { pause() }
        case .ended:
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue ?? 0)
            if wasPlayingBeforeInterruption && options.contains(.shouldResume) {
                resume()
            }
            wasPlayingBeforeInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChange(reasonValue: UInt?) {
        guard let raw = reasonValue,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        // Headphones unplugged / AirPods removed: pause instead of blasting the speaker.
        if reason == .oldDeviceUnavailable && isPlaying {
            pause()
        }
    }

    private func handleEngineConfigurationChange() {
        guard isPlaying else { return }
        let time = position(of: active)
        abortCrossfade()
        stopSlot(active)
        start(active, at: time)
        isPlaying = active.running
        publish()
    }

    // MARK: - Lock screen / Control Center

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            guard let self, self.active.file != nil else { return .noActionableNowPlayingItem }
            self.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self, self.active.file != nil else { return .noActionableNowPlayingItem }
            self.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.next()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.previous()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(to: event.positionTime)
            return .success
        }
    }

    /// Publishes the current song/duration and refreshes the lock screen.
    private func publish() {
        currentSong = queue.currentItem
        duration = active.file != nil ? active.duration : (currentSong?.duration ?? 0)
        let time = position(of: active)
        if abs(clock.time - time) > 0.01 { clock.time = time }
        shuffleMode = queue.shuffleMode
        repeatMode = queue.repeatMode
        updateNowPlaying()
    }

    private func updateNowPlaying() {
        guard let song = currentSong else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artist,
            MPMediaItemPropertyAlbumTitle: song.album,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position(of: active),
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(rate)
        ]
        // One artwork object per song, not one per play/pause/seek.
        if nowPlayingArtwork?.key != song.key {
            nowPlayingArtwork = (song.key, song.artworkData.flatMap(UIImage.init(data:)).map(Self.makeArtwork))
        }
        if let artwork = nowPlayingArtwork?.artwork {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // Built outside the main actor because iOS may call this closure on a background thread.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
