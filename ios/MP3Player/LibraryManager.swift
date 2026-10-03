import Foundation
import Combine
import AVFoundation

/// One folder that songs were found in, for the Folders tab.
struct FolderGroup: Identifiable, Hashable {
    let key: String
    let name: String
    let detail: String
    let songCount: Int
    let isHidden: Bool
    var id: String { key }
}

/// What happened when a folder was picked to link.
enum LinkResult {
    case linked
    /// The folder (or one containing it) is already in the library.
    case alreadyIncluded
    case noAccess
}

/// A place songs are read from: the app's Documents folder or a linked folder.
nonisolated struct LibraryRoot: Sendable {
    let prefix: String          // "docs" or "link:<uuid>"
    let url: URL
    let name: String
}

/// Owns the songs: the app's Documents folder (including subfolders) plus
/// folders linked from the Files app, which are read in place.
@MainActor
final class LibraryManager: ObservableObject {
    /// The library without songs in hidden folders (what every tab shows).
    @Published private(set) var songs: [Song] = []
    /// Every song found, hidden folders included (for the Folders tab).
    @Published private(set) var allSongs: [Song] = [] {
        didSet { applyHiddenFolders(settings.settings.hiddenFolders) }
    }
    @Published private(set) var isScanning = false
    /// Grouped once per library change (not on every redraw of the tabs).
    @Published private(set) var albums: [AlbumGroup] = []
    @Published private(set) var artists: [ArtistGroup] = []
    @Published private(set) var folders: [FolderGroup] = []
    @Published private(set) var stats = LibraryStats([])

    nonisolated static let audioExtensions: Set<String> = ["mp3", "m4a", "m4b", "aac", "wav", "aiff", "aif",
                                                           "flac", "caf"]

    private let settings: SettingsStore
    /// Linked folders whose security scope is open (kept open while the app runs).
    private var openFolders: [UUID: URL] = [:]
    private var reloadTask: Task<Void, Never>?
    /// Display names of the roots from the last scan ("docs" -> "On This iPhone", ...).
    private var rootNames: [String: String] = [:]
    private var cancellables = Set<AnyCancellable>()

    init(settings: SettingsStore) {
        self.settings = settings
        settings.$settings
            .map(\.hiddenFolders)
            .removeDuplicates()
            .sink { [weak self] hidden in self?.applyHiddenFolders(hidden) }
            .store(in: &cancellables)
    }

    private func applyHiddenFolders(_ hidden: Set<String>) {
        let visible = hidden.isEmpty ? allSongs : allSongs.filter { !Self.isHidden($0.folderKey, by: hidden) }
        songs = visible
        albums = AlbumGroup.make(from: visible)
        artists = ArtistGroup.make(from: visible)
        stats = LibraryStats(visible)
        folders = folderGroups(hidden: hidden)
    }

    nonisolated static func isHidden(_ folderKey: String, by hidden: Set<String>) -> Bool {
        hidden.contains { folderKey == $0 || folderKey.hasPrefix($0 + "/") }
    }

    func hideFolder(_ key: String) {
        settings.update { $0.hiddenFolders.insert(key) }
    }

    func showFolder(_ key: String) {
        settings.update { $0.hiddenFolders.remove(key) }
    }

    var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    // MARK: - Scanning

    private var lastScan: Date?
    private var lastDocumentsChange: Date?

    /// Rescans when coming back to the app, but only if something may have changed:
    /// files were added to / removed from Documents (e.g. through Finder), or it's been
    /// a while (linked folders can change elsewhere). Pull to refresh always rescans.
    func reloadIfStale() async {
        let documentsChange = (try? documents.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate
        if let lastScan, Date().timeIntervalSince(lastScan) < 600, documentsChange == lastDocumentsChange {
            return
        }
        await reload()
    }

    /// Rescans everything. Unchanged files are taken from the previous scan, so this is quick.
    func reload() async {
        if let reloadTask {
            await reloadTask.value
            return
        }
        let task = Task { await performReload() }
        reloadTask = task
        await task.value
        reloadTask = nil
    }

    private func performReload() async {
        isScanning = true
        defer { isScanning = false }
        lastScan = Date()
        lastDocumentsChange = (try? documents.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate
        let scanRoots = roots()
        rootNames = Dictionary(scanRoots.map { ($0.prefix, $0.name) }, uniquingKeysWith: { first, _ in first })
        let previous = Dictionary(allSongs.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        let scanned = await Task.detached(priority: .userInitiated) {
            await Self.scan(roots: scanRoots, previous: previous)
        }.value
        allSongs = scanned.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func song(forKey key: String) -> Song? {
        allSongs.first { $0.key == key }
    }

    /// Re-reads one file (after its tags were edited) and returns the updated song.
    @discardableResult
    func refresh(_ song: Song) async -> Song? {
        guard let root = roots().first(where: { song.key.hasPrefix($0.prefix + "/") }) else { return nil }
        let updated = await Task.detached(priority: .userInitiated) {
            await Self.scan(roots: [root], previous: [:], only: song.url)
        }.value.first
        if let updated, let index = allSongs.firstIndex(where: { $0.key == song.key }) {
            allSongs[index] = updated
        }
        return updated
    }

    nonisolated private static func scan(roots: [LibraryRoot], previous: [String: Song],
                                         only: URL? = nil) async -> [Song] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .addedToDirectoryDateKey, .creationDateKey,
                                      .contentModificationDateKey, .fileSizeKey]
        var result: [Song] = []
        // The same file can be reachable twice (a linked folder containing another): keep the first.
        var seenPaths = Set<String>()

        for root in roots {
            let rootPath = root.url.resolvingSymlinksInPath().path
            let folders = Self.filesByFolder(in: root.url, keys: keys)

            for (folderPath, files) in folders {
                var lyricsByName: [String: URL] = [:]
                var coverURL: URL?
                for url in files {
                    let ext = url.pathExtension.lowercased()
                    let base = url.deletingPathExtension().lastPathComponent.lowercased()
                    if ext == "lrc" || (ext == "txt" && lyricsByName[base] == nil) {
                        lyricsByName[base] = url
                    } else if ["jpg", "jpeg", "png"].contains(ext),
                              ["cover", "folder", "front", "album", "albumart"].contains(base) {
                        coverURL = coverURL ?? url
                    }
                }
                var folderCover: Data?
                var coverLoaded = false

                let relativeFolder = folderPath.count > rootPath.count
                    ? String(folderPath.dropFirst(rootPath.count + 1)) : ""
                let folderKey = relativeFolder.isEmpty ? root.prefix : "\(root.prefix)/\(relativeFolder)"

                for url in files where audioExtensions.contains(url.pathExtension.lowercased()) {
                    if let only, only.standardizedFileURL != url.standardizedFileURL { continue }
                    guard seenPaths.insert(url.resolvingSymlinksInPath().path).inserted else { continue }
                    let fileName = url.lastPathComponent
                    let key = relativeFolder.isEmpty ? "\(root.prefix)/\(fileName)"
                        : "\(root.prefix)/\(relativeFolder)/\(fileName)"
                    let values = try? url.resourceValues(forKeys: Set(keys))
                    let modified = values?.contentModificationDate ?? .distantPast
                    let lyrics = lyricsByName[url.deletingPathExtension().lastPathComponent.lowercased()]

                    if var cached = previous[key], cached.fileModified == modified {
                        cached.lyricsURL = lyrics
                        result.append(cached)
                        continue
                    }

                    let tags = await TagIO.read(url)
                    var song = Song(
                        url: url, key: key,
                        title: tags.title.isEmpty ? url.deletingPathExtension().lastPathComponent : tags.title,
                        artist: tags.artist.isEmpty ? "Unknown Artist" : tags.artist,
                        album: tags.album.isEmpty ? "Unknown Album" : tags.album,
                        dateAdded: values?.addedToDirectoryDate ?? values?.creationDate ?? .distantPast)
                    song.albumArtist = tags.albumArtist
                    song.genre = tags.genre
                    song.year = Int(tags.year.prefix(4)) ?? 0
                    song.trackNumber = Int(tags.track) ?? 0
                    song.discNumber = Int(tags.disc) ?? 0
                    song.replayGainDb = tags.replayGainDb
                    song.fileModified = modified
                    song.fileSize = Int64(values?.fileSize ?? 0)
                    song.lyricsURL = lyrics
                    song.folderKey = folderKey
                    if let audio = try? AVAudioFile(forReading: url), audio.fileFormat.sampleRate > 0 {
                        song.sampleRate = Int(audio.fileFormat.sampleRate)
                        song.duration = Double(audio.length) / audio.fileFormat.sampleRate
                    }
                    if song.duration > 0 {
                        song.bitrateKbps = Int(Double(song.fileSize) * 8 / song.duration / 1000)
                    }
                    if let artwork = tags.artwork {
                        // Kept at Now Playing size (≤900 px), not the file's full size.
                        song.artworkData = ImageTools.shrinkCover(artwork) ?? artwork
                    } else if let coverURL {
                        if !coverLoaded {
                            folderCover = (try? Data(contentsOf: coverURL)).map { ImageTools.shrinkCover($0) ?? $0 }
                            coverLoaded = true
                        }
                        song.artworkData = folderCover
                    }
                    result.append(song)
                }
            }
        }
        return result
    }

    /// Every file under `root`, grouped by folder (so lyrics and cover images can be matched).
    /// Synchronous because directory enumerators can't be iterated in async code.
    nonisolated private static func filesByFolder(in root: URL, keys: [URLResourceKey]) -> [String: [URL]] {
        guard let enumerator = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { return [:] }
        var result: [String: [URL]] = [:]
        while let url = enumerator.nextObject() as? URL {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
            result[url.deletingLastPathComponent().resolvingSymlinksInPath().path, default: []].append(url)
        }
        return result
    }

    // MARK: - Folders

    private func roots() -> [LibraryRoot] {
        [LibraryRoot(prefix: "docs", url: documents, name: "On This iPhone")] + openLinkedFolders()
    }

    /// Resolves every linked folder's bookmark and opens its security scope (once).
    private func openLinkedFolders() -> [LibraryRoot] {
        var roots: [LibraryRoot] = []
        var usedPaths = [documents.resolvingSymlinksInPath().path]
        var duplicates: [UUID] = []
        for folder in settings.settings.linkedFolders {
            let url: URL
            if let open = openFolders[folder.id] {
                url = open
            } else {
                var stale = false
                guard let resolved = try? URL(resolvingBookmarkData: folder.bookmark, options: [],
                                              relativeTo: nil, bookmarkDataIsStale: &stale),
                      resolved.startAccessingSecurityScopedResource() else { continue }
                openFolders[folder.id] = resolved
                if stale, let fresh = try? resolved.bookmarkData() {
                    settings.update { settings in
                        if let i = settings.linkedFolders.firstIndex(where: { $0.id == folder.id }) {
                            settings.linkedFolders[i].bookmark = fresh
                        }
                    }
                }
                url = resolved
            }
            // Linked twice, or inside a folder that's already included: drop it.
            let path = url.resolvingSymlinksInPath().path
            if usedPaths.contains(where: { path == $0 || path.hasPrefix($0 + "/") }) {
                duplicates.append(folder.id)
                continue
            }
            usedPaths.append(path)
            roots.append(LibraryRoot(prefix: "link:\(folder.id.uuidString)", url: url, name: folder.name))
        }
        if !duplicates.isEmpty {
            for id in duplicates { openFolders.removeValue(forKey: id)?.stopAccessingSecurityScopedResource() }
            settings.update { $0.linkedFolders.removeAll { duplicates.contains($0.id) } }
        }
        return roots
    }

    /// Links a folder picked in the Files app; its songs are read where they are.
    func linkFolder(_ url: URL) async -> LinkResult {
        let path = url.resolvingSymlinksInPath().path
        let included = [documents.resolvingSymlinksInPath().path]
            + openFolders.values.map { $0.resolvingSymlinksInPath().path }
        if included.contains(where: { path == $0 || path.hasPrefix($0 + "/") }) {
            return .alreadyIncluded
        }
        guard url.startAccessingSecurityScopedResource() else { return .noAccess }
        guard let bookmark = try? url.bookmarkData() else {
            url.stopAccessingSecurityScopedResource()
            return .noAccess
        }
        let folder = LinkedFolder(name: url.lastPathComponent, bookmark: bookmark)
        openFolders[folder.id] = url
        settings.update { $0.linkedFolders.append(folder) }
        await reload()
        return .linked
    }

    func unlinkFolder(_ id: UUID) async {
        openFolders.removeValue(forKey: id)?.stopAccessingSecurityScopedResource()
        settings.update { $0.linkedFolders.removeAll { $0.id == id } }
        await reload()
    }

    /// The folders songs were found in, sorted by name.
    private func folderGroups(hidden: Set<String>) -> [FolderGroup] {
        return Dictionary(grouping: allSongs, by: \.folderKey)
            .map { key, items in
                let prefix = key.split(separator: "/", maxSplits: 1).first.map(String.init) ?? key
                let path = key.count > prefix.count ? String(key.dropFirst(prefix.count + 1)) : ""
                let rootName = rootNames[prefix] ?? prefix
                return FolderGroup(key: key,
                                   name: path.isEmpty ? rootName : (path as NSString).lastPathComponent,
                                   detail: path.isEmpty ? "" : "\(rootName)/\(path)",
                                   songCount: items.count,
                                   isHidden: Self.isHidden(key, by: hidden))
            }
            .sorted { $0.detail.localizedStandardCompare($1.detail) == .orderedAscending }
    }

    // MARK: - Importing and deleting

    /// Copies files picked in the Files app (audio, .lrc, .txt) into the app's own storage.
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

    /// Deletes songs (and their lyrics files).
    func delete(_ songsToDelete: [Song]) async {
        for song in songsToDelete {
            try? FileManager.default.removeItem(at: song.url)
            if let lyrics = song.lyricsURL {
                try? FileManager.default.removeItem(at: lyrics)
            }
        }
        await reload()
    }
}

// MARK: - Library stats and grouping helpers

struct LibraryStats {
    let songs: Int
    let albums: Int
    let artists: Int
    let genres: Int

    init(_ songs: [Song]) {
        self.songs = songs.count
        albums = Set(songs.map(\.album)).count
        artists = Set(songs.map(\.artist)).count
        genres = Set(songs.map(\.genre).filter { !$0.isEmpty }).count
    }
}
