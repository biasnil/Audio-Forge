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

/// A–B repeat: off, A marked, or looping between A and B.
nonisolated enum ABRepeat: Equatable {
    case off
    case aSet(TimeInterval)
    case looping(TimeInterval, TimeInterval)
}

/// A song the user queued by hand (Play Next / Add to Queue).
nonisolated struct QueuedSong: Identifiable, Equatable, Sendable {
    let id: UUID
    let song: Song

    init(_ song: Song) {
        id = UUID()
        self.song = song
    }
}

/// What gets saved so the app can resume where you left off.
nonisolated private struct SavedPlayerState: Codable {
    var queueKeys: [String]
    var index: Int
    var time: TimeInterval
    var shuffleMode: ShuffleMode
    var repeatMode: RepeatMode
    var rate: Float
    // Added later (optional so older saved states still load).
    var manualKeys: [String]?
    var currentManualKey: String?
    var contextName: String?
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
/// One slot plays the current song; for a hand-off the other one starts the next song —
/// fading it in (crossfade, optionally tempo- and beat-matched) or starting it on the
/// exact sample the current one ends (gapless) — and then becomes the current one.
/// The EQ's global gain carries the volume (up to 200%) and the EQ post-gain.
///
/// What plays next: songs queued by hand (Play Next / Add to Queue) first, then the
/// list playback was started from (`PlaybackQueue`, with shuffle and repeat).
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
    @Published private(set) var abRepeat: ABRepeat = .off
    /// Songs queued by hand; they play before the rest of the list.
    @Published private(set) var manualQueue: [QueuedSong] = []
    /// Recently played songs, newest last.
    @Published private(set) var history: [Song] = []
    /// What playback was started from ("Album: …", "Playlist: …"), for Up Next.
    @Published private(set) var contextName: String?
    /// Bumped whenever what plays next may have changed (Up Next re-reads it).
    @Published private(set) var queueVersion = 0
    /// Set when nothing in the queue can be played; the UI shows an alert and clears it.
    @Published var errorMessage: String?
    /// A short, non-blocking message ("Skipped X — can't be played"); the UI shows a banner.
    @Published var notice: String?

    /// Long tracks (audiobooks, mixes) get ±15 s buttons and resume where you stopped.
    static let longTrackSeconds: TimeInterval = 600
    var isLongTrack: Bool { duration >= Self.longTrackSeconds }

    /// One player node and its effects chain.
    private final class Slot {
        let player = AVAudioPlayerNode()
        let timePitch = AVAudioUnitTimePitch()
        let gain = AVAudioUnitEQ(numberOfBands: 1)
        let fader = AVAudioMixerNode()
        var connectedFormat: AVAudioFormat?
        var file: AVAudioFile?
        var song: Song?
        var analysis: SongAnalysis?
        /// Smart transitions: where the audio really starts and ends (silence trimmed).
        var beginTime: TimeInterval = 0
        var endTime: TimeInterval = 0
        /// First and end frame of the segment that's scheduled now.
        var startFrame: AVAudioFramePosition = 0
        var endFrame: AVAudioFramePosition = 0
        /// Tempo change on top of the playback speed (beat-matched crossfades).
        var rateFactor: Float = 1
        /// Position while not running.
        var pausedTime: TimeInterval = 0
        /// The last position read from the engine while running. Used when the engine can't
        /// answer any more (an interruption or route change already stopped it), so the
        /// position isn't lost and playback doesn't jump back to where the segment started.
        var lastPosition: TimeInterval = 0
        var running = false
        /// Bumped whenever the scheduled segment is replaced, so stale completions are ignored.
        var generation = 0

        var duration: TimeInterval {
            guard let file, file.processingFormat.sampleRate > 0 else { return song?.duration ?? 0 }
            return Double(file.length) / file.processingFormat.sampleRate
        }

        /// Where this slot's song ends right now (the end of the scheduled segment).
        var segmentEndTime: TimeInterval {
            guard let file, file.processingFormat.sampleRate > 0 else { return duration }
            return Double(endFrame) / file.processingFormat.sampleRate
        }

        init() {
            gain.bands[0].bypass = true
        }
    }

    private let settings: SettingsStore
    private let songData: SongDataStore
    /// The latest settings (@Published delivers them before `settings.settings` changes).
    private var audio: AppSettings
    private let engine = AVAudioEngine()
    private let mix = AVAudioMixerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: EqualizerConfig.bandCount)
    private let slots = [Slot(), Slot()]
    private var activeIndex = 0
    private var active: Slot { slots[activeIndex] }
    private var incoming: Slot { slots[1 - activeIndex] }
    /// A hand-off to the next song is in progress in the other slot: a crossfade, or
    /// (when `gapless`) the next song scheduled to start exactly when this one ends.
    private var crossfading = false
    private var gapless = false
    /// Length of the crossfade in progress (smart fades can be longer than the setting).
    private var fadeLength: TimeInterval = 5
    /// The next song couldn't be loaded for a fade: don't retry on every tick.
    private var crossfadeBlocked = false

    private var queue = PlaybackQueue<Song>()
    /// The hand-picked song playing now (nil when the current song comes from `queue`).
    private var currentManual: Song?
    private enum PendingNext { case manual(QueuedSong), context }
    /// Which song plays next, decided when a hand-off is prepared.
    private var pendingNext: PendingNext?

    private var wasPlayingBeforeInterruption = false
    private var sleepTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var tickCount = 0
    private var hasRestored = false
    /// The song whose play has been counted (once per play, after half of it or 4 minutes).
    private var countedKey: String?
    private var cancellables = Set<AnyCancellable>()
    private var nowPlayingArtwork: (key: String, artwork: MPMediaItemArtwork?)?
    private let visualizerTap = VisualizerTap()

    private static let stateKey = "savedPlayerState.v2"
    private static let legacyStateKey = "savedPlayerState"

    private var smartEnabled: Bool { audio.analysisCompleted && audio.smartTransitions }
    private var beatMatchEnabled: Bool { audio.analysisCompleted && audio.beatMatchedCrossfade }

    init(settings: SettingsStore, songData: SongDataStore) {
        self.settings = settings
        self.songData = songData
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
        // Harmonic shuffle uses the latest analysis (it grows while "Analyze" runs).
        songData.$analysis
            .debounce(for: .seconds(2), scheduler: RunLoop.main)
            .sink { [weak self] analysis in self?.updateSmartOrder(analysis: analysis) }
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

    // MARK: - Visualizer

    /// The spectrum bars on Now Playing (only measured while they're shown).
    let visualizer = VisualizerData()

    func setVisualizerActive(_ on: Bool) {
        if on {
            visualizerTap.install(on: eq, data: visualizer)
        } else {
            visualizerTap.remove(from: eq)
            visualizer.levels = Array(repeating: 0, count: VisualizerData.bandCount)
        }
    }

    // MARK: - Slots

    private func effectiveRate(_ slot: Slot) -> Double { Double(rate * slot.rateFactor) }

    private func applyRate(_ slot: Slot) {
        slot.timePitch.rate = rate * slot.rateFactor
    }

    /// Opens a song's file in `slot` (stopped, at the start). False if it can't be read.
    private func load(_ song: Song, into slot: Slot) -> Bool {
        stopSlot(slot)
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: song.url)
        } catch {
            slot.file = nil
            slot.song = nil
            slot.analysis = nil
            return false
        }
        if slot.connectedFormat != file.processingFormat {
            connectPlayer(slot, format: file.processingFormat)
        }
        slot.file = file
        slot.song = song
        slot.analysis = songData.analysis(for: song)
        slot.startFrame = 0
        slot.pausedTime = 0
        slot.rateFactor = 1
        applyRate(slot)
        applyReplayGain(slot)
        applyTrim(slot)
        return true
    }

    /// Smart transitions: skip the silence at the start and end of the song.
    private func applyTrim(_ slot: Slot) {
        let duration = slot.duration
        slot.beginTime = 0
        slot.endTime = duration
        guard smartEnabled, let analysis = slot.analysis else { return }
        if analysis.audioStart > 0.3 { slot.beginTime = max(0, analysis.audioStart - 0.05) }
        if analysis.audioEnd < duration - 0.3 { slot.endTime = min(duration, analysis.audioEnd + 0.1) }
        if slot.endTime <= slot.beginTime + 1 {
            slot.beginTime = 0
            slot.endTime = duration
        }
    }

    /// Starts `slot` playing from `time`, now or at `hostTime` (gapless / beat-matched hand-off).
    private func start(_ slot: Slot, at time: TimeInterval, startingAt hostTime: AVAudioTime? = nil) {
        guard let file = slot.file, startEngineIfNeeded() else { return }
        stopSlot(slot)
        let sampleRate = file.processingFormat.sampleRate
        let startFrame = min(max(AVAudioFramePosition(time * sampleRate), 0), file.length)
        // Play up to the trimmed end, unless the start is already past it (a seek there).
        let trimmedEnd = min(AVAudioFramePosition(slot.endTime * sampleRate), file.length)
        let endFrame = startFrame < trimmedEnd - AVAudioFramePosition(sampleRate * 0.5) ? trimmedEnd : file.length
        let frameCount = AVAudioFrameCount(max(endFrame - startFrame, 0))
        slot.startFrame = startFrame
        slot.endFrame = endFrame
        slot.pausedTime = Double(startFrame) / sampleRate
        slot.lastPosition = slot.pausedTime
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
        slot.player.play(at: hostTime)
        slot.running = true
    }

    /// The host time at which `slot` reaches song position `target` (seconds), from its last render.
    private func hostTime(of slot: Slot, reaching target: TimeInterval) -> AVAudioTime? {
        guard let file = slot.file, slot.running, engine.isRunning,
              let nodeTime = slot.player.lastRenderTime, nodeTime.isHostTimeValid, nodeTime.isSampleTimeValid,
              let playerTime = slot.player.playerTime(forNodeTime: nodeTime) else { return nil }
        let now = Double(slot.startFrame + playerTime.sampleTime) / file.processingFormat.sampleRate
        let seconds = max((target - now) / effectiveRate(slot), 0)
        return AVAudioTime(hostTime: nodeTime.hostTime + AVAudioTime.hostTime(forSeconds: seconds))
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
        guard slot.running else { return slot.pausedTime }
        guard engine.isRunning,
              let nodeTime = slot.player.lastRenderTime, nodeTime.isSampleTimeValid,
              let playerTime = slot.player.playerTime(forNodeTime: nodeTime) else { return slot.lastPosition }
        let time = Double(slot.startFrame + playerTime.sampleTime) / file.processingFormat.sampleRate
        // Never earlier than what was already reached (a fresh segment can briefly report 0).
        let position = min(max(time, slot.lastPosition), slot.duration)
        slot.lastPosition = position
        return position
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
        slot.pausedTime = slot.segmentEndTime
        guard slot === active else { return }      // a (very short) song fading in ended: ignore
        if crossfading { finalizeCrossfade() } else { advance(automatic: true) }
    }

    // MARK: - What plays next

    /// The song after the current one: a hand-queued song first, then the list. Decided once
    /// per hand-off (calling again returns the same pick until it's committed or cancelled).
    private func peekNextSong() -> Song? {
        if let pendingNext {
            switch pendingNext {
            case .manual(let queued): return queued.song
            case .context: return queue.pendingNextItem
            }
        }
        if let first = manualQueue.first {
            pendingNext = .manual(first)
            return first.song
        }
        guard queue.peekNext(), let song = queue.pendingNextItem else { return nil }
        pendingNext = .context
        return song
    }

    private func commitNextSong() {
        switch pendingNext {
        case .manual(let queued):
            manualQueue.removeAll { $0.id == queued.id }
            currentManual = queued.song
        case .context:
            currentManual = nil
            queue.commitPendingNext()
        case nil:
            break
        }
        pendingNext = nil
        queueVersion &+= 1
    }

    private func cancelNextSong() {
        if case .context? = pendingNext { queue.cancelPendingNext() }
        pendingNext = nil
    }

    private func moveToNextSong() -> Bool {
        guard peekNextSong() != nil else { return false }
        commitNextSong()
        return true
    }

    private var queueCurrentSong: Song? { currentManual ?? queue.currentItem }

    // MARK: - Transport

    /// Replaces the list with `songs` and plays the one at `index`. Hand-queued songs stay queued.
    func play(_ songs: [Song], startAt index: Int, from context: String? = nil) {
        guard songs.indices.contains(index) else { return }
        abortCrossfade()
        cancelNextSong()
        currentManual = nil
        contextName = context
        queue.setQueue(songs, startIndex: index)
        playCurrent()
    }

    /// Plays a list in smart shuffle (each song once), starting from a random song.
    func playShuffled(_ songs: [Song], from context: String? = nil) {
        guard !songs.isEmpty else { return }
        if queue.shuffleMode == .off { queue.setShuffleMode(.smart) }
        play(songs, startAt: Int.random(in: songs.indices), from: context)
    }

    /// Hard cut to the current song.
    private func playCurrent(at time: TimeInterval = 0, autoplay: Bool = true) {
        rememberLongTrackPosition()
        guard var song = queueCurrentSong else { return }
        crossfadeBlocked = false
        let slot = active
        slot.fader.outputVolume = 1
        // A song that can't be opened (deleted, corrupt, not downloaded) is skipped, so one
        // bad file doesn't stop the whole queue. Gives up after trying everything once.
        var skipped: [String] = []
        let attempts = queue.items.count + manualQueue.count + 1
        while !load(song, into: slot) {
            skipped.append(song.fileName)
            guard skipped.count < attempts, moveToNextSong(), let next = queueCurrentSong else {
                errorMessage = skipped.count == 1 ? "Couldn't play \(song.fileName)."
                    : "None of the next \(skipped.count) songs could be played."
                isPlaying = false
                engine.pause()
                publish()
                return
            }
            song = next
        }
        if !skipped.isEmpty {
            notice = skipped.count == 1 ? "Skipped \(skipped[0]) — it can't be played."
                : "Skipped \(skipped.count) songs that can't be played."
        }
        var startTime = skipped.isEmpty ? time : 0
        if startTime == 0 {
            startTime = resumePosition(for: song, in: slot) ?? slot.beginTime
        }
        if autoplay {
            start(slot, at: startTime)
        } else {
            slot.pausedTime = min(startTime, slot.duration)
        }
        songStarted(song)
        isPlaying = autoplay && slot.running
        publish()
        saveState()
    }

    /// Bookkeeping when a new song becomes the current one.
    private func songStarted(_ song: Song) {
        countedKey = nil
        abRepeat = .off
        if history.last?.key != song.key {
            history.append(song)
            if history.count > 50 { history.removeFirst(history.count - 50) }
        }
        queueVersion &+= 1
    }

    /// Picks up exactly where `pause()` stopped, including a crossfade in progress:
    /// both songs continue from their own positions at the same fade volumes.
    func resume() {
        guard active.file != nil, !active.running else { return }
        start(active, at: active.pausedTime)
        if crossfading, incoming.file != nil { start(incoming, at: incoming.pausedTime) }
        isPlaying = active.running
        publish()
    }

    /// Pauses without losing a crossfade: both slots keep their positions and volumes.
    func pause() {
        if gapless { abortCrossfade() }          // re-scheduled near the end after resuming
        stopSlot(active)
        if crossfading { stopSlot(incoming) }
        isPlaying = false
        engine.pause()
        rememberLongTrackPosition()
        publish()
        saveState()
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    /// A manual skip always hard-cuts, dropping any fade in progress.
    func next() {
        abortCrossfade()
        cancelNextSong()
        advance(automatic: false)
    }

    func previous() {
        abortCrossfade()
        cancelNextSong()
        let wasPlaying = isPlaying || active.running
        if position(of: active) - active.beginTime > 3 {
            seek(to: active.beginTime)           // restart the current song
        } else if currentManual != nil {
            currentManual = nil                  // back to the list's song before the queued one
            playCurrent(autoplay: wasPlaying)
        } else if queue.movePrevious() {
            playCurrent(autoplay: wasPlaying)
        } else {
            seek(to: active.beginTime)
        }
    }

    /// ±15 s (long tracks).
    func skip(by seconds: TimeInterval) {
        seek(to: position(of: active) + seconds)
    }

    /// automatic = the song finished by itself.
    private func advance(automatic: Bool) {
        guard queueCurrentSong != nil else { return }

        if automatic && sleepTimer == .endOfTrack {
            // Sleep timer "end of current song": just stop here.
            setSleepTimer(.off)
            stopAtEnd()
        } else if automatic && repeatMode == .one {
            start(active, at: active.beginTime)
            songStarted(active.song ?? queueCurrentSong!)
        } else if moveToNextSong() {
            playCurrent(autoplay: automatic || isPlaying)
        } else {
            stopAtEnd()
        }
    }

    /// End of the queue: stop and rewind the last song.
    private func stopAtEnd() {
        stopSlot(active)
        active.pausedTime = active.beginTime
        isPlaying = false
        engine.pause()
        engine.mainMixerNode.outputVolume = 1
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
        if case .looping(let a, let b) = abRepeat, target < a || target > b { abRepeat = .off }
        clock.time = target
        updateNowPlaying()
    }

    // MARK: - Queue (Up Next)

    /// Puts songs right after the current one.
    func playNext(_ songs: [Song]) {
        guard !songs.isEmpty else { return }
        if queueCurrentSong == nil {
            play(songs, startAt: 0)
            return
        }
        settleHandOff()
        manualQueue.insert(contentsOf: songs.map(QueuedSong.init), at: 0)
        queueChanged(songs.count == 1 ? "\(songs[0].title) plays next." : "\(songs.count) songs play next.")
    }

    /// Adds songs at the end of the hand-picked queue (before the rest of the list).
    func addToQueue(_ songs: [Song]) {
        guard !songs.isEmpty else { return }
        if queueCurrentSong == nil {
            play(songs, startAt: 0)
            return
        }
        settleHandOff()
        manualQueue.append(contentsOf: songs.map(QueuedSong.init))
        queueChanged(songs.count == 1 ? "Added \(songs[0].title) to the queue."
                     : "Added \(songs.count) songs to the queue.")
    }

    func removeFromQueue(at offsets: IndexSet) {
        settleHandOff()
        manualQueue.remove(atOffsets: offsets)
        queueChanged(nil)
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        settleHandOff()
        manualQueue.move(fromOffsets: source, toOffset: destination)
        queueChanged(nil)
    }

    func clearQueue() {
        settleHandOff()
        manualQueue.removeAll()
        queueChanged(nil)
    }

    /// Plays a hand-queued song now (the ones before it are dropped).
    func jumpToQueued(_ id: UUID) {
        guard let index = manualQueue.firstIndex(where: { $0.id == id }) else { return }
        abortCrossfade()
        cancelNextSong()
        currentManual = manualQueue[index].song
        manualQueue.removeFirst(index + 1)
        playCurrent()
    }

    /// The list's upcoming songs (index in the list, song), or nil in Random shuffle.
    func upcomingFromList(limit: Int = 300) -> [(index: Int, song: Song)]? {
        queue.upcoming(limit: limit).map { indices in indices.map { ($0, queue.items[$0]) } }
    }

    /// Plays a song from the list's upcoming part now.
    func jumpToListItem(_ index: Int) {
        abortCrossfade()
        cancelNextSong()
        currentManual = nil
        queue.jump(to: index)
        playCurrent()
    }

    func removeFromList(_ index: Int) {
        settleHandOff()
        queue.remove(at: index)
        queueChanged(nil)
    }

    /// The current song and everything after it (for "Save as Playlist").
    func queueSnapshot() -> [Song] {
        var songs: [Song] = []
        if let current = queueCurrentSong { songs.append(current) }
        songs += manualQueue.map(\.song)
        songs += (upcomingFromList(limit: 5000) ?? []).map { $0.song }
        return songs
    }

    private func queueChanged(_ message: String?) {
        if let message { notice = message }
        queueVersion &+= 1
        saveState()
    }

    // MARK: - Tick: transitions, play counts, A–B repeat, sleep fade

    private func tick() {
        tickCount += 1
        guard active.file != nil else { return }
        let position = position(of: active)
        let end = active.running ? active.segmentEndTime : active.endTime

        if crossfading {
            _ = self.position(of: incoming)        // keeps its last known position fresh too
            updateCrossfade(position: position, end: end)
        } else if active.running, !crossfadeBlocked, repeatMode != .one, sleepTimer != .endOfTrack,
                  abRepeat == .off {
            prepareHandOff(position: position, end: end)
        }

        if active.running {
            // Glide back to normal speed after a beat-matched crossfade.
            if !crossfading, active.rateFactor != 1 {
                let difference = 1 - active.rateFactor
                active.rateFactor = abs(difference) < 0.002 ? 1 : active.rateFactor + difference * 0.06
                applyRate(active)
            }
            // A–B repeat.
            if case .looping(let a, let b) = abRepeat, position >= b {
                start(active, at: a)
            }
            countPlayIfDue(position: position)
            if tickCount % 2 == 0, abs(clock.time - position) > 0.05 {
                clock.time = position             // 0.2 s keeps synced lyrics responsive
            }
        }
        applySleepFade(position: position, end: end)
    }

    /// Lines up the next song before this one ends: a crossfade, or a gapless hand-off.
    private func prepareHandOff(position: TimeInterval, end: TimeInterval) {
        let remaining = (end - position) / effectiveRate(active)
        let fade = Double(audio.crossfadeSeconds)
        // Don't decide anything until the end is near.
        guard remaining <= max(fade * 2.5, 12) else { return }
        guard let next = peekNextSong() else { return }

        var useCrossfade = audio.crossfadeEnabled && active.duration > fade * 2
        // Smart: consecutive songs of the same album stay gapless (live albums, DJ mixes).
        if useCrossfade, smartEnabled, let current = active.song,
           AlbumGroup.key(for: current) == AlbumGroup.key(for: next), !current.album.isEmpty {
            useCrossfade = false
        }
        if useCrossfade {
            var length = fade
            // Smart: a long fade-out at the end becomes the crossfade.
            if smartEnabled, let analysis = active.analysis {
                let outro = end - analysis.outroStart
                if outro > fade { length = min(outro, fade * 2, 12) }
            }
            if remaining <= length { startCrossfade(gapless: false, length: length, next: next) }
        } else if remaining <= 1.5 {
            startCrossfade(gapless: true, length: 0, next: next)
        }
    }

    private func startCrossfade(gapless: Bool, length: TimeInterval, next: Song) {
        guard repeatMode != .one, sleepTimer != .endOfTrack else { return }
        // Gapless: start the next song on the exact sample this one ends (timed before
        // loading, while this slot's timing is known). If it can't be timed, the normal
        // advance at the end still plays it, just with a tiny gap.
        let handOff = gapless ? hostTime(of: active, reaching: active.segmentEndTime) : nil
        if gapless && handOff == nil { return }
        let slot = incoming
        guard load(next, into: slot) else {
            // Gets another go (and is skipped if it still can't be played) on the normal advance.
            crossfadeBlocked = true
            cancelNextSong()
            return
        }
        var startTime = slot.beginTime
        var startAt = handOff
        if !gapless, beatMatchEnabled, let match = beatMatch(into: slot) {
            slot.rateFactor = match.rateFactor
            applyRate(slot)
            startTime = match.startPosition
            startAt = match.hostTime
        }
        slot.fader.outputVolume = gapless ? 1 : 0
        start(slot, at: startTime, startingAt: startAt)
        self.gapless = gapless
        fadeLength = max(length, 1)
        crossfading = true
    }

    /// Beat-matched crossfade: the next song's tempo is adjusted to this one's (if they're
    /// within 8%, counting half/double time) and it starts on its first beat exactly on
    /// one of this song's beats. nil when either song's beat isn't reliable.
    private func beatMatch(into slot: Slot) -> (rateFactor: Float, startPosition: TimeInterval, hostTime: AVAudioTime)? {
        guard let outgoing = active.analysis, let incomingAnalysis = slot.analysis,
              outgoing.isBeatReliable, incomingAnalysis.isBeatReliable,
              let outBPM = outgoing.bpm, let inBPM = incomingAnalysis.bpm,
              let outPeriod = outgoing.beatPeriod, let inPeriod = incomingAnalysis.beatPeriod else { return nil }
        let ratio = [outBPM / inBPM, outBPM * 2 / inBPM, outBPM / (inBPM * 2)]
            .min { abs($0 - 1) < abs($1 - 1) } ?? 1
        guard abs(ratio - 1) <= 0.08 else { return nil }

        // This song's next beat, at least 0.15 s away.
        let now = position(of: active)
        var beats = ((now - outgoing.firstBeat) / outPeriod).rounded(.up)
        var beatTime = outgoing.firstBeat + beats * outPeriod
        if beatTime - now < 0.15 {
            beats += 1
            beatTime = outgoing.firstBeat + beats * outPeriod
        }
        guard let host = hostTime(of: active, reaching: beatTime) else { return nil }
        // The next song's first beat at or after where its audio starts.
        let skippedBeats = max(0, ((slot.beginTime - incomingAnalysis.firstBeat) / inPeriod).rounded(.up))
        let startPosition = max(0, incomingAnalysis.firstBeat + skippedBeats * inPeriod)
        return (Float(ratio), startPosition, host)
    }

    private func updateCrossfade(position: TimeInterval, end: TimeInterval) {
        guard !gapless else { return }           // switches over when the song actually ends
        let remaining = (end - position) / effectiveRate(active)
        let progress = Float(1 - min(max(remaining / fadeLength, 0), 1))
        active.fader.outputVolume = 1 - progress
        // The incoming song may not have started yet (beat-matched start on a beat).
        incoming.fader.outputVolume = progress
        if remaining <= 0.05 { finalizeCrossfade() }
    }

    /// The faded-in slot becomes the active one.
    private func finalizeCrossfade() {
        guard crossfading else { return }
        crossfading = false
        gapless = false
        rememberLongTrackPosition()
        let old = active
        activeIndex = 1 - activeIndex
        active.fader.outputVolume = 1
        stopSlot(old)
        old.file = nil
        old.song = nil
        old.analysis = nil
        commitNextSong()
        crossfadeBlocked = false
        if let song = active.song { songStarted(song) }
        isPlaying = active.running
        publish()
        saveState()
    }

    /// Drops a hand-off in progress, leaving the current song at full volume. Keeps the pick.
    private func abortCrossfade() {
        guard crossfading else { return }
        crossfading = false
        gapless = false
        stopSlot(incoming)
        incoming.file = nil
        incoming.song = nil
        incoming.analysis = nil
        active.fader.outputVolume = 1
    }

    /// Before what plays next changes: an audible crossfade is finished (the next song is
    /// already playing); a hand-off that hasn't been heard yet is cancelled.
    private func settleHandOff() {
        if crossfading && !gapless {
            finalizeCrossfade()
        } else {
            abortCrossfade()
            cancelNextSong()
        }
    }

    /// Counts a play once half the song (or 4 minutes) has been heard.
    private func countPlayIfDue(position: TimeInterval) {
        guard let song = active.song, countedKey != song.key else { return }
        let needed = min(max(active.duration * 0.5, 1), 240)
        if position - active.beginTime >= needed {
            countedKey = song.key
            songData.recordPlay(song.key)
        }
    }

    // MARK: - A–B repeat

    /// Off → mark A → mark B (loop) → off.
    func cycleABRepeat() {
        let now = position(of: active)
        switch abRepeat {
        case .off:
            abRepeat = .aSet(now)
        case .aSet(let a):
            abRepeat = now > a + 1 ? .looping(a, now) : .off
            if case .looping = abRepeat { settleHandOff() }
        case .looping:
            abRepeat = .off
        }
    }

    // MARK: - Long tracks

    /// Where a long track was left, if it should continue from there.
    private func resumePosition(for song: Song, in slot: Slot) -> TimeInterval? {
        guard audio.rememberLongTrackPosition, slot.duration >= Self.longTrackSeconds,
              let saved = songData.stats(for: song.key).resumePosition,
              saved > 15, saved < slot.duration - 30 else { return nil }
        return saved
    }

    /// Saves (or clears, near the end) the position of the long track that's playing.
    private func rememberLongTrackPosition() {
        guard audio.rememberLongTrackPosition, let song = active.song,
              active.duration >= Self.longTrackSeconds else { return }
        let position = position(of: active)
        let keep = position > 15 && position < active.duration - 30
        songData.setResumePosition(song.key, keep ? position : nil)
    }

    // MARK: - Shuffle / Repeat / Speed / Volume

    func cycleShuffle() {
        // Changing the mode drops the list's next pick, so settle a hand-off first.
        settleHandOff()
        queue.cycleShuffleMode()
        shuffleMode = queue.shuffleMode
        queueVersion &+= 1
        saveState()
    }

    func cycleRepeat() {
        settleHandOff()
        queue.cycleRepeatMode()
        repeatMode = queue.repeatMode
        queueVersion &+= 1
        saveState()
    }

    func setRate(_ newRate: Float) {
        if gapless { abortCrossfade() }          // its start time was computed for the old speed
        rate = newRate
        slots.forEach(applyRate)
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
        let old = audio
        audio = new
        for (band, gain) in zip(eq.bands, new.eqGainsDb) {
            band.gain = gain
            band.bypass = !new.eqEnabled
        }
        applyOutputGain(new)
        slots.forEach(applyReplayGain)
        if !new.crossfadeEnabled && crossfading && !gapless { abortCrossfade() }
        if old.smartTransitions != new.smartTransitions || old.analysisCompleted != new.analysisCompleted {
            // Trim points only change for songs loaded from now on; the current one keeps its segment.
            if !crossfading { cancelNextSong() }
        }
        if old.harmonicShuffle != new.harmonicShuffle || old.analysisCompleted != new.analysisCompleted {
            updateSmartOrder(analysis: songData.analysis)
            settleHandOff()
            queue.reshuffle()
            queueVersion &+= 1
        }
    }

    /// Harmonic shuffle: Smart shuffle orders songs so neighbours have compatible keys and tempos.
    private func updateSmartOrder(analysis: [String: SongAnalysis]) {
        guard audio.analysisCompleted && audio.harmonicShuffle else {
            queue.smartOrder = nil
            return
        }
        queue.smartOrder = { items, start in Self.harmonicOrder(items, start: start, analysis: analysis) }
    }

    /// Greedy DJ-style order: from the current song, the next one is the best match among
    /// a few random candidates (so it stays a shuffle, just a smooth one).
    nonisolated private static func harmonicOrder(_ items: [Song], start: Int,
                                                  analysis: [String: SongAnalysis]) -> [Int] {
        guard items.indices.contains(start) else { return Array(items.indices) }
        var pool = Array(items.indices)
        pool.swapAt(start, pool.count - 1)
        pool.removeLast()
        var order = [start]
        var current = start
        while !pool.isEmpty {
            var bestPosition = Int.random(in: pool.indices)
            var bestScore = -Double.infinity
            for _ in 0..<min(10, pool.count) {
                let position = Int.random(in: pool.indices)
                let score = compatibility(analysis[items[current].key], analysis[items[pool[position]].key])
                    + Double.random(in: 0..<0.5)
                if score > bestScore {
                    bestScore = score
                    bestPosition = position
                }
            }
            current = pool[bestPosition]
            order.append(current)
            pool.swapAt(bestPosition, pool.count - 1)
            pool.removeLast()
        }
        return order
    }

    nonisolated private static func compatibility(_ a: SongAnalysis?, _ b: SongAnalysis?) -> Double {
        guard let a, let b else { return 0 }
        var score = 0.0
        if let keyA = a.key, let keyB = b.key {
            if keyA == keyB { score += 3 } else if keyA.isCompatible(with: keyB) { score += 2 }
        }
        if let bpmA = a.bpm, let bpmB = b.bpm {
            let ratio = [bpmA / bpmB, bpmA * 2 / bpmB, bpmA / (bpmB * 2)].map { max($0, 1 / $0) }.min() ?? 2
            if ratio <= 1.06 { score += 2 } else if ratio <= 1.12 { score += 1 }
        }
        return score
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
        engine.mainMixerNode.outputVolume = 1
        if timer == .endOfTrack && crossfading {
            // Stop after the song that's playing: don't hand off to the next one.
            abortCrossfade()
            cancelNextSong()
        }

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
            self.engine.mainMixerNode.outputVolume = 1
        }
    }

    /// Fades the volume out over the last 30 s of a sleep timer (last 10 s for "end of song").
    private func applySleepFade(position: TimeInterval, end: TimeInterval) {
        var volume: Float = 1
        if audio.sleepFadeOut && active.running {
            if let sleepEndDate {
                volume = Float(min(max(sleepEndDate.timeIntervalSinceNow / 30, 0), 1))
            } else if sleepTimer == .endOfTrack {
                volume = Float(min(max((end - position) / effectiveRate(active) / 10, 0), 1))
            }
        }
        if abs(engine.mainMixerNode.outputVolume - volume) > 0.005 {
            engine.mainMixerNode.outputVolume = volume
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
            cancelNextSong()
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
        manualQueue = manualQueue.map { $0.song.key == updated.key ? QueuedSong(updated) : $0 }
        if currentManual?.key == updated.key { currentManual = updated }
        if let hold {
            playCurrent(at: max(hold.time, 0.01), autoplay: hold.wasPlaying)
        } else {
            publish()
        }
    }

    // MARK: - Resume where you left off

    func saveState() {
        rememberLongTrackPosition()
        guard !queue.isEmpty || currentManual != nil else {
            UserDefaults.standard.removeObject(forKey: Self.stateKey)
            return
        }
        let state = SavedPlayerState(
            queueKeys: queue.items.map(\.key),
            index: queue.currentIndex,
            time: position(of: active),
            shuffleMode: queue.shuffleMode,
            repeatMode: queue.repeatMode,
            rate: rate,
            manualKeys: manualQueue.map(\.song.key),
            currentManualKey: currentManual?.key,
            contextName: contextName
        )
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: Self.stateKey)
        }
    }

    /// Loads the last song (paused, at the same position) once the library is ready.
    func restoreIfNeeded(from library: [Song]) {
        guard !hasRestored, !library.isEmpty else { return }
        hasRestored = true
        guard queue.isEmpty, currentManual == nil else { return }

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
        let found = currentKey.flatMap { key in songs.firstIndex { $0.key == key } }
        let manualCurrent = state.currentManualKey.flatMap { byKey[$0] }
        guard !songs.isEmpty || manualCurrent != nil else { return }

        queue.setShuffleMode(state.shuffleMode)
        queue.setRepeatMode(state.repeatMode)
        if !songs.isEmpty { queue.setQueue(songs, startIndex: found ?? 0) }
        manualQueue = (state.manualKeys ?? []).compactMap { byKey[$0] }.map(QueuedSong.init)
        currentManual = manualCurrent
        contextName = state.contextName
        shuffleMode = queue.shuffleMode
        repeatMode = queue.repeatMode
        setRate(state.rate)
        let sameSong = manualCurrent != nil ? state.currentManualKey == manualCurrent?.key : found != nil
        playCurrent(at: sameSong ? max(state.time, 0.01) : 0, autoplay: false)
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

    /// The engine stopped itself (output changed, e.g. AirPods connected): carry on from the
    /// same positions, keeping a crossfade going.
    private func handleEngineConfigurationChange() {
        guard isPlaying else { return }
        if gapless || (crossfading && !incoming.running) { abortCrossfade() }
        stopSlot(active)
        if crossfading { stopSlot(incoming) }
        start(active, at: active.pausedTime)
        if crossfading, incoming.file != nil { start(incoming, at: incoming.pausedTime) }
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
        // ±15 s, shown on the lock screen for long tracks only (see publish()).
        center.skipForwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.skip(by: 15)
            return .success
        }
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.skip(by: -15)
            return .success
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
    }

    /// Publishes the current song/duration and refreshes the lock screen.
    private func publish() {
        currentSong = queueCurrentSong
        duration = active.file != nil ? active.duration : (currentSong?.duration ?? 0)
        let time = position(of: active)
        if abs(clock.time - time) > 0.01 { clock.time = time }
        shuffleMode = queue.shuffleMode
        repeatMode = queue.repeatMode
        let center = MPRemoteCommandCenter.shared()
        if center.skipForwardCommand.isEnabled != isLongTrack {
            center.skipForwardCommand.isEnabled = isLongTrack
            center.skipBackwardCommand.isEnabled = isLongTrack
        }
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
        let artworkKey = "\(song.key)|\(song.artworkID ?? "")"
        if nowPlayingArtwork?.key != artworkKey {
            nowPlayingArtwork = (artworkKey,
                                 ArtworkStore.load(song.artworkID).flatMap(UIImage.init(data:)).map(Self.makeArtwork))
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
