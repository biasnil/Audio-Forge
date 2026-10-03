import Foundation

/// One playable file, from the app's Documents folder or from a linked folder.
nonisolated struct Song: Identifiable, Hashable, Sendable {
    let url: URL
    /// Stable identity, saved in playlists and the resume state:
    /// "docs/<path inside Documents>" or "link:<folder id>/<path inside that folder>".
    let key: String
    var title: String
    var artist: String
    var album: String
    var albumArtist: String = ""
    var genre: String = ""
    var year: Int = 0
    var trackNumber: Int = 0
    var discNumber: Int = 0
    var duration: TimeInterval = 0
    var bitrateKbps: Int = 0
    var sampleRate: Int = 0
    var fileSize: Int64 = 0
    /// Embedded cover art, or else cover.jpg / folder.jpg / ... next to the file.
    var artworkData: Data?
    var dateAdded: Date
    /// Used to skip re-reading unchanged files on a rescan.
    var fileModified: Date = .distantPast
    /// Same-name .lrc (or .txt) next to the song.
    var lyricsURL: URL?
    var replayGainDb: Float?
    /// Key of the folder the song is in (same prefix scheme as `key`), for the Folders tab.
    var folderKey: String = ""

    var id: String { key }
    var fileName: String { url.lastPathComponent }
}
