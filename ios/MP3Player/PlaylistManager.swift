import Foundation
import Combine

/// Songs are stored by key ("docs/<path>" or "link:<folder>/<path>"), because the
/// app's folder path can change between launches. (Older versions stored bare file names.)
nonisolated struct Playlist: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var songFiles: [String] = []
    /// Your own cover image (file name in Application Support/PlaylistCovers); nil = album-cover grid.
    var coverFile: String?
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
    /// Your rule-based playlists (the built-in smart lists are `SmartPlaylist.builtIn`).
    @Published private(set) var smartPlaylists: [SmartPlaylist] = [] {
        didSet { saveSmart() }
    }

    private let fileURL: URL
    private let smartURL: URL

    init() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        fileURL = folder.appendingPathComponent("playlists.json")
        smartURL = folder.appendingPathComponent("smartplaylists.json")
        load()
        if let data = try? Data(contentsOf: smartURL),
           let decoded = try? JSONDecoder().decode([SmartPlaylist].self, from: data) {
            smartPlaylists = decoded
        }
    }

    // MARK: - Smart playlists

    /// Adds a new smart playlist or replaces the one with the same id.
    func saveSmart(_ playlist: SmartPlaylist) {
        if let i = smartPlaylists.firstIndex(where: { $0.id == playlist.id }) {
            smartPlaylists[i] = playlist
        } else {
            smartPlaylists.append(playlist)
        }
    }

    func deleteSmart(at offsets: IndexSet) {
        smartPlaylists.remove(atOffsets: offsets)
    }

    private func saveSmart() {
        do {
            try JSONEncoder().encode(smartPlaylists).write(to: smartURL, options: .atomic)
        } catch {
            print("Could not save smart playlists: \(error)")
        }
    }

    // MARK: - Covers

    nonisolated static var coverFolder: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PlaylistCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    nonisolated static func coverURL(_ file: String) -> URL {
        coverFolder.appendingPathComponent(file)
    }

    /// Uses a picked image as the playlist's cover (scaled down and saved as JPEG).
    func setCover(_ imageData: Data, for id: UUID) {
        guard let i = index(of: id), let jpeg = TagIO.prepareCover(imageData) else { return }
        let file = "\(id.uuidString)-\(Int(Date().timeIntervalSince1970)).jpg"
        do {
            try jpeg.write(to: Self.coverURL(file), options: .atomic)
        } catch {
            return
        }
        if let old = playlists[i].coverFile { try? FileManager.default.removeItem(at: Self.coverURL(old)) }
        playlists[i].coverFile = file
    }

    func removeCover(for id: UUID) {
        guard let i = index(of: id), let old = playlists[i].coverFile else { return }
        try? FileManager.default.removeItem(at: Self.coverURL(old))
        playlists[i].coverFile = nil
    }

    // MARK: - .m3u import

    /// Creates a playlist from an .m3u / .m3u8 file, matching its entries to library songs by
    /// path, then file name, then "Artist - Title". Returns (matched, total entries).
    @discardableResult
    func importM3U(_ url: URL, library: [Song]) -> (matched: Int, total: Int)? {
        let hasAccess = url.startAccessingSecurityScopedResource()
        defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
        else { return nil }

        func normalize(_ path: String) -> String {
            path.replacingOccurrences(of: "\\", with: "/").lowercased()
        }
        // Lookups: path inside its root, file name, and "artist - title".
        var byPath: [String: Song] = [:]
        var byName: [String: Song] = [:]
        var byTitle: [String: Song] = [:]
        for song in library {
            let path = song.key.split(separator: "/", maxSplits: 1).last.map(String.init) ?? song.fileName
            byPath[normalize(path)] = byPath[normalize(path)] ?? song
            byName[song.fileName.lowercased()] = byName[song.fileName.lowercased()] ?? song
            let label = "\(song.artist) - \(song.title)".lowercased()
            byTitle[label] = byTitle[label] ?? song
        }

        var keys: [String] = []
        var total = 0
        var pendingLabel: String?
        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.uppercased().hasPrefix("#EXTINF:") {
                pendingLabel = line.split(separator: ",", maxSplits: 1).last.map {
                    String($0).trimmingCharacters(in: .whitespaces).lowercased()
                }
                continue
            }
            if line.hasPrefix("#") { continue }
            total += 1
            let path = normalize(line.removingPercentEncoding ?? line)
                .replacingOccurrences(of: "file://", with: "")
            let fileName = (path as NSString).lastPathComponent
            // Longest path suffix that's in the library ("Music/Rock/a.mp3" → "rock/a.mp3" …).
            var match: Song?
            let parts = path.split(separator: "/").map(String.init)
            for start in parts.indices where match == nil {
                match = byPath[parts[start...].joined(separator: "/")]
            }
            match = match ?? byName[fileName] ?? pendingLabel.flatMap { byTitle[$0] }
            if let match, !keys.contains(match.key) { keys.append(match.key) }
            pendingLabel = nil
        }
        guard total > 0 else { return nil }
        let name = url.deletingPathExtension().lastPathComponent
        var playlist = Playlist(name: name.isEmpty ? "Imported Playlist" : name)
        playlist.songFiles = keys
        playlists.append(playlist)
        return (keys.count, total)
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
