import Foundation
import Combine

/// Songs are stored by key ("docs/<path>" or "link:<folder>/<path>"), because the
/// app's folder path can change between launches. (Older versions stored bare file names.)
nonisolated struct Playlist: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var songFiles: [String] = []
}

/// One row of a playlist: the song, or the key of a file that's gone.
struct PlaylistEntry: Identifiable {
    let key: String
    let song: Song?
    var id: String { key }
}

/// Creates, edits and saves playlists (Application Support/playlists.json).
@MainActor
final class PlaylistManager: ObservableObject {
    @Published private(set) var playlists: [Playlist] = [] {
        didSet { save() }
    }

    private let fileURL: URL

    init() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        fileURL = folder.appendingPathComponent("playlists.json")
        load()
    }

    // MARK: - Editing

    @discardableResult
    func create(name: String) -> UUID {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let playlist = Playlist(name: trimmed.isEmpty ? "New Playlist" : trimmed)
        playlists.append(playlist)
        return playlist.id
    }

    func rename(_ id: UUID, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let i = index(of: id) else { return }
        playlists[i].name = trimmed
    }

    func delete(at offsets: IndexSet) {
        playlists = playlists.enumerated()
            .filter { !offsets.contains($0.offset) }
            .map(\.element)
    }

    /// Adds songs to the end, skipping any already in the playlist.
    func add(_ songs: [Song], to id: UUID) {
        guard let i = index(of: id) else { return }
        var files = playlists[i].songFiles
        for song in songs {
            if !files.contains(song.key) { files.append(song.key) }
        }
        playlists[i].songFiles = files
    }

    /// Replaces the playlist's contents (used after reordering or removing entries).
    func setKeys(_ keys: [String], for id: UUID) {
        guard let i = index(of: id) else { return }
        playlists[i].songFiles = keys
    }

    /// Turns stored keys back into Song objects, skipping files that are gone.
    func songs(in playlist: Playlist, from library: [Song]) -> [Song] {
        entries(in: playlist, from: library).compactMap(\.song)
    }

    /// Every stored key, with its song if the file is still there.
    func entries(in playlist: Playlist, from library: [Song]) -> [PlaylistEntry] {
        let byKey = Dictionary(library.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        return playlist.songFiles.map { PlaylistEntry(key: $0, song: byKey[$0]) }
    }

    private func index(of id: UUID) -> Int? {
        playlists.firstIndex { $0.id == id }
    }

    // MARK: - Saving

    private func save() {
        do {
            let data = try JSONEncoder().encode(playlists)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("Could not save playlists: \(error)")
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([Playlist].self, from: data) else { return }
        // Older versions stored bare file names from the Documents folder.
        playlists = decoded.map { playlist in
            var migrated = playlist
            migrated.songFiles = playlist.songFiles.map { $0.contains("/") ? $0 : "docs/\($0)" }
            return migrated
        }
    }
}
