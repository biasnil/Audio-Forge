import Foundation

/// What the Now Playing widget shows. The app writes it to the App Group folder whenever the
/// song or play/pause changes (WidgetBridge.swift); the widget only reads it.
nonisolated struct NowPlayingSnapshot: Codable, Equatable, Sendable {
    var title: String
    var artist: String
    var isPlaying: Bool
    /// The cover's ArtworkStore id, so the widget's copy is only rewritten when it changes.
    var artworkID: String?
}

/// The folder the app and the widget share (App Group, set in both entitlements files).
nonisolated enum NowPlayingShared {
    static let appGroup = "group.com.biasnil.audioforge"
    static let widgetKind = "NowPlaying"

    static var folder: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }
    static var snapshotURL: URL? { folder?.appendingPathComponent("nowplaying.json") }
    /// The current cover, shrunk for the widget (widgets have little memory).
    static var coverURL: URL? { folder?.appendingPathComponent("nowplaying-cover.jpg") }

    static func load() -> NowPlayingSnapshot? {
        guard let url = snapshotURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(NowPlayingSnapshot.self, from: data)
    }
}
