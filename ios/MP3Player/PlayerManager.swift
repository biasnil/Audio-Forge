import Foundation
import Combine
import AVFoundation
import MediaPlayer
import UIKit

nonisolated enum RepeatMode: String, Codable {
    case off, all, one

    var iconName: String { self == .one ? "repeat.1" : "repeat" }
}

nonisolated enum SleepTimer: Hashable {
    case off
    case minutes(Int)
    case endOfTrack
}

/// What gets saved so the app can resume where you left off.
nonisolated private struct SavedPlayerState: Codable {
    var queueFiles: [String]
    var originalFiles: [String]
    var index: Int
    var time: TimeInterval
    var shuffled: Bool
    var repeatMode: RepeatMode
    var rate: Float
}

/// Handles playback, the queue, shuffle/repeat, speed, sleep timer,
/// background audio, lock-screen controls and resuming on launch.
@MainActor
final class PlayerManager: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var queue: [Song] = []
    @Published private(set) var currentIndex: Int?
    @Published private(set) var isPlaying = false
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var isShuffled = false
    @Published private(set) var repeatMode: RepeatMode = .off
    @Published private(set) var rate: Float = 1.0
    @Published private(set) var sleepTimer: SleepTimer = .off
    @Published private(set) var sleepEndDate: Date?

    private var player: AVAudioPlayer?
    private var originalQueue: [Song] = []          // un-shuffled order
    private var wasPlayingBeforeInterruption = false
    private var sleepTask: Task<Void, Never>?
    private var hasRestored = false
    private static let stateKey = "savedPlayerState"

    var currentSong: Song? {
        guard let i = currentIndex, queue.indices.contains(i) else { return nil }
        return queue[i]
    }

    override init() {
        super.init()
        do {
            // .playback keeps audio going when the phone is locked or on silent.
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        } catch {
            print("Audio session error: \(error)")
        }
        setupRemoteCommands()
        observeSessionEvents()
    }

    // MARK: - Playback

    func play(_ songs: [Song], startAt index: Int) {
        guard songs.indices.contains(index) else { return }
        originalQueue = songs

        if isShuffled {
            // Tapped song plays first, the rest in random order.
            var rest = songs
            let first = rest.remove(at: index)
            queue = [first] + rest.shuffled()
            load(index: 0)
        } else {
            queue = songs
            load(index: index)
        }
    }

    /// Plays a list with shuffle on, starting from a random song.
    func playShuffled(_ songs: [Song]) {
        guard !songs.isEmpty else { return }
        isShuffled = true
        play(songs, startAt: Int.random(in: songs.indices))
    }

    private func load(index: Int, autoplay: Bool = true) {
        guard queue.indices.contains(index) else { return }
        do {
            let newPlayer = try AVAudioPlayer(contentsOf: queue[index].url)
            newPlayer.delegate = self
            newPlayer.enableRate = true          // must be set before playing for speed control
            newPlayer.prepareToPlay()
            newPlayer.rate = rate
            if autoplay {
                try AVAudioSession.sharedInstance().setActive(true)
                newPlayer.play()
            }

            player = newPlayer
            currentIndex = index
            duration = newPlayer.duration
            currentTime = 0
            isPlaying = autoplay
            updateNowPlaying()
            saveState()
        } catch {
            print("Could not play \(queue[index].url.lastPathComponent): \(error)")
        }
    }

    func resume() {
        guard let player else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
        isPlaying = player.isPlaying
        updateNowPlaying()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        updateNowPlaying()
        saveState()
    }

    func togglePlayPause() {
        guard let player else { return }
        if player.isPlaying { pause() } else { resume() }
    }

    func next() {
        advance(automatic: false)
    }

    /// automatic = the song finished by itself.
    private func advance(automatic: Bool) {
        guard let i = currentIndex, !queue.isEmpty else { return }

        if automatic && sleepTimer == .endOfTrack {
            // Sleep timer "end of current song": just stop here.
            isPlaying = false
            setSleepTimer(.off)
            updateNowPlaying()
            saveState()
        } else if automatic && repeatMode == .one {
            seek(to: 0)
            resume()
        } else if i + 1 < queue.count {
            load(index: i + 1)
        } else if repeatMode != .off {
            load(index: 0)                       // wrap around
        } else {
            // End of queue: stop and rewind.
            player?.stop()
            player?.currentTime = 0
            isPlaying = false
            currentTime = 0
            updateNowPlaying()
            saveState()
        }
    }

    func previous() {
        guard let i = currentIndex, let player else { return }
        if player.currentTime > 3 {
            seek(to: 0)                          // restart current song
        } else if i > 0 {
            load(index: i - 1)
        } else if repeatMode != .off {
            load(index: queue.count - 1)         // wrap to last song
        } else {
            seek(to: 0)
        }
    }

    func seek(to time: TimeInterval) {
        guard let player else { return }
        player.currentTime = time
        currentTime = time
        updateNowPlaying()
    }

    func refreshTime() {
        if let player { currentTime = player.currentTime }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.advance(automatic: true) }
    }

    // MARK: - Shuffle / Repeat / Speed

    func toggleShuffle() {
        isShuffled.toggle()
        guard let current = currentSong else { return }

        if isShuffled {
            let rest = originalQueue.filter { $0.id != current.id }.shuffled()
            queue = [current] + rest
            currentIndex = 0
        } else {
            queue = originalQueue
            currentIndex = originalQueue.firstIndex { $0.id == current.id } ?? 0
        }
    }

    func cycleRepeat() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    func setRate(_ newRate: Float) {
        rate = newRate
        player?.rate = newRate
        updateNowPlaying()
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

    // MARK: - Resume where you left off

    func saveState() {
        guard let i = currentIndex, !queue.isEmpty else {
            UserDefaults.standard.removeObject(forKey: Self.stateKey)
            return
        }
        let state = SavedPlayerState(
            queueFiles: queue.map { $0.url.lastPathComponent },
            originalFiles: originalQueue.map { $0.url.lastPathComponent },
            index: i,
            time: player?.currentTime ?? 0,
            shuffled: isShuffled,
            repeatMode: repeatMode,
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

        guard currentIndex == nil,
              let data = UserDefaults.standard.data(forKey: Self.stateKey),
              let state = try? JSONDecoder().decode(SavedPlayerState.self, from: data) else { return }

        let byFile = Dictionary(library.map { ($0.url.lastPathComponent, $0) },
                                uniquingKeysWith: { first, _ in first })
        queue = state.queueFiles.compactMap { byFile[$0] }
        originalQueue = state.originalFiles.compactMap { byFile[$0] }
        guard !queue.isEmpty else { return }

        isShuffled = state.shuffled
        repeatMode = state.repeatMode
        rate = state.rate
        load(index: min(state.index, queue.count - 1), autoplay: false)
        seek(to: min(state.time, duration))
    }

    // MARK: - Interruptions (calls, alarms) and headphone unplugging

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
    }

    private func handleInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard let raw = typeValue,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }

        switch type {
        case .began:
            // iOS already paused the player (e.g. a phone call came in).
            wasPlayingBeforeInterruption = isPlaying
            isPlaying = false
            updateNowPlaying()
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
        if reason == .oldDeviceUnavailable {
            pause()
        }
    }

    // MARK: - Lock screen / Control Center

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            guard let self, self.player != nil else { return .noActionableNowPlayingItem }
            self.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self, self.player != nil else { return .noActionableNowPlayingItem }
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
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player?.currentTime ?? 0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(rate)
        ]
        if let data = song.artworkData, let image = UIImage(data: data) {
            info[MPMediaItemPropertyArtwork] = Self.makeArtwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // Built outside the main actor because iOS may call this closure on a background thread.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
