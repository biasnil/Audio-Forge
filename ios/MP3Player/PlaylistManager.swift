import Foundation
import Combine

/// Songs are stored by file name, because the app's folder path can change between launches.
nonisolated struct Playlist: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var songFiles: [String] = []
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
            let file = song.url.lastPathComponent
            if !files.contains(file) { files.append(file) }
        }
        playlists[i].songFiles = files
    }

    /// Replaces the playlist's contents (used after reordering or removing songs).
    func setSongs(_ songs: [Song], for id: UUID) {
        guard let i = index(of: id) else { return }
        playlists[i].songFiles = songs.map { $0.url.lastPathComponent }
    }

    /// Turns stored file names back into Song objects, skipping deleted files.
    func songs(in playlist: Playlist, from library: [Song]) -> [Song] {
        let byFile = Dictionary(library.map { ($0.url.lastPathComponent, $0) },
                                uniquingKeysWith: { first, _ in first })
        return playlist.songFiles.compactMap { byFile[$0] }
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
        playlists = decoded
    }
}
