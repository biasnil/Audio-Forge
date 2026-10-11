import Foundation
import WidgetKit

/// Connects the app to the Now Playing widget: writes what's playing to the App Group folder
/// (only when the song or play/pause changes) and runs the widget's buttons.
@MainActor
enum WidgetBridge {
    private static var shown: NowPlayingSnapshot?
    private static var hasLoaded = false
    /// Writes run one after another, so a quick play/pause can't land before the song change.
    private static var writing: Task<Void, Never>?

    /// Called from PlayerManager.publish().
    static func update(song: Song?, isPlaying: Bool) {
        if !hasLoaded {
            shown = NowPlayingShared.load()
            hasLoaded = true
        }
        let snapshot = song.map {
            NowPlayingSnapshot(title: $0.title, artist: $0.artist, isPlaying: isPlaying, artworkID: $0.artworkID)
        }
        guard snapshot != shown else { return }
        let coverChanged = snapshot?.artworkID != shown?.artworkID
        shown = snapshot

        let previous = writing
        writing = Task.detached(priority: .utility) {
            await previous?.value
            write(snapshot, coverChanged: coverChanged)
            WidgetCenter.shared.reloadTimelines(ofKind: NowPlayingShared.widgetKind)
        }
    }

    nonisolated private static func write(_ snapshot: NowPlayingSnapshot?, coverChanged: Bool) {
        guard let snapshotURL = NowPlayingShared.snapshotURL, let coverURL = NowPlayingShared.coverURL else { return }
        if coverChanged {
            if let data = ArtworkStore.load(snapshot?.artworkID) {
                try? (ImageTools.shrinkCover(data, maxPixels: 400) ?? data).write(to: coverURL, options: .atomic)
            } else {
                try? FileManager.default.removeItem(at: coverURL)
            }
        }
        if let snapshot, let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: snapshotURL, options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: snapshotURL)
        }
    }

    /// A widget button. If iOS just started the app for it, the last song is loaded first
    /// (paused, where it was), as the phone screen and CarPlay do on launch.
    static func perform(_ command: PlaybackCommand) async {
        let player = AppObjects.player
        if player.currentSong == nil {
            let library = AppObjects.library
            await library.reloadIfStale()
            player.restoreIfNeeded(from: library.songs)
        }
        switch command {
        case .playPause: player.togglePlayPause()
        case .next: player.next()
        case .previous: player.previous()
        }
    }
}
