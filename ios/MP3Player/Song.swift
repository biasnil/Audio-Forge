import Foundation

struct Song: Identifiable, Hashable {
    let url: URL
    let title: String
    let artist: String
    let album: String
    let artworkData: Data?
    let dateAdded: Date
    let lyricsURL: URL?        // matching .lrc file, if any

    var id: URL { url }
}
