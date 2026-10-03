import Foundation
import Combine
import AVFoundation

/// Owns the song (and .lrc lyrics) files stored in the app's Documents folder.
@MainActor
final class LibraryManager: ObservableObject {
    @Published private(set) var songs: [Song] = []

    private let supportedExtensions: Set<String> = ["mp3", "m4a", "aac", "wav", "aiff", "flac"]

    private var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Rescans the Documents folder (also picks up files added via Finder).
    func reload() async {
        let keys: [URLResourceKey] = [.addedToDirectoryDateKey, .creationDateKey]
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: documents, includingPropertiesForKeys: keys)) ?? []

        // "Song Name.lrc" belongs to "Song Name.mp3".
        var lyricsByName: [String: URL] = [:]
        for url in urls where url.pathExtension.lowercased() == "lrc" {
            lyricsByName[url.deletingPathExtension().lastPathComponent.lowercased()] = url
        }

        var loaded: [Song] = []
        for url in urls where supportedExtensions.contains(url.pathExtension.lowercased()) {
            let values = try? url.resourceValues(forKeys: Set(keys))
            let added = values?.addedToDirectoryDate ?? values?.creationDate ?? .distantPast
            let lyrics = lyricsByName[url.deletingPathExtension().lastPathComponent.lowercased()]
            loaded.append(await Self.makeSong(from: url, dateAdded: added, lyricsURL: lyrics))
        }
        songs = loaded.sorted {
            $0.title.localizedStandardCompare($1.title) == .orderedAscending
        }
    }

    /// Copies files picked in the Files app (audio or .lrc) into the app's own storage.
    func importFiles(_ urls: [URL]) async {
        for url in urls {
            let hasAccess = url.startAccessingSecurityScopedResource()
            defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }

            let destination = documents.appendingPathComponent(url.lastPathComponent)
            do {
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.copyItem(at: url, to: destination)
            } catch {
                print("Import failed for \(url.lastPathComponent): \(error)")
            }
        }
        await reload()
    }

    /// Deletes songs (and their .lrc files).
    func delete(_ songsToDelete: [Song]) async {
        for song in songsToDelete {
            try? FileManager.default.removeItem(at: song.url)
            if let lyrics = song.lyricsURL {
                try? FileManager.default.removeItem(at: lyrics)
            }
        }
        await reload()
    }

    /// Reads ID3 tags (title, artist, album, cover art), falling back to the file name.
    private static func makeSong(from url: URL, dateAdded: Date, lyricsURL: URL?) async -> Song {
        var title = url.deletingPathExtension().lastPathComponent
        var artist = "Unknown Artist"
        var album = "Unknown Album"
        var artwork: Data?

        let asset = AVURLAsset(url: url)
        if let items = try? await asset.load(.commonMetadata) {
            for item in items {
                switch item.commonKey {
                case .commonKeyTitle:
                    if let value = try? await item.load(.stringValue), !value.isEmpty { title = value }
                case .commonKeyArtist:
                    if let value = try? await item.load(.stringValue), !value.isEmpty { artist = value }
                case .commonKeyAlbumName:
                    if let value = try? await item.load(.stringValue), !value.isEmpty { album = value }
                case .commonKeyArtwork:
                    artwork = try? await item.load(.dataValue)
                default:
                    break
                }
            }
        }
        return Song(url: url, title: title, artist: artist, album: album,
                    artworkData: artwork, dateAdded: dateAdded, lyricsURL: lyricsURL)
    }
}
