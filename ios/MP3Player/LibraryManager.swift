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
    @Published private(set) var genres: [GenreGroup] = []
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
        genres = GenreGroup.make(from: visible)
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

    /// While files are being read: (read so far, to read). nil otherwise.
    @Published private(set) var scanProgress: (done: Int, total: Int)?

    /// Rescans when coming back to the app. Unchanged files aren't re-read, and the library
    /// is only replaced (and the tabs redrawn) if something actually changed.
    func reloadIfStale() async {
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
        defer {
            isScanning = false
            scanProgress = nil
        }
        let scanRoots = roots()
        rootNames = Dictionary(scanRoots.map { ($0.prefix, $0.name) }, uniquingKeysWith: { first, _ in first })
        let previous = Dictionary(allSongs.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        // On the first scan, show songs as they're read instead of after the whole library.
        let showPartial = allSongs.isEmpty
        let scanned = await Task.detached(priority: .userInitiated) {
            await Self.scan(roots: scanRoots, previous: previous) { done, total, partial in
                await self.scanProgressed(done: done, total: total, partial: showPartial ? partial : nil)
            }
        }.value
        let sorted = Self.sortedByTitle(scanned)
        if !Self.sameLibrary(sorted, allSongs) { allSongs = sorted }
    }

    private func scanProgressed(done: Int, total: Int, partial: [Song]?) {
        scanProgress = (done, total)
        if let partial { allSongs = Self.sortedByTitle(partial) }
    }

    nonisolated private static func sortedByTitle(_ songs: [Song]) -> [Song] {
        songs.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    /// Same songs, same files, same lyrics and covers (in the same order).
    nonisolated private static func sameLibrary(_ a: [Song], _ b: [Song]) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).allSatisfy { x, y in
            x.key == y.key && x.fileModified == y.fileModified && x.lyricsURL == y.lyricsURL
                && x.artworkID == y.artworkID && x.title == y.title
        }
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

    /// A file that has to be read (new or changed since the last scan).
    nonisolated private struct ScanItem: Sendable {
        let url: URL
        let key: String
        let folderKey: String
        let lyricsURL: URL?
        let folderCoverID: String?
        let modified: Date
        let added: Date
        let size: Int
    }

    nonisolated private static let resourceKeys: [URLResourceKey] = [
        .isRegularFileKey, .addedToDirectoryDateKey, .creationDateKey, .contentModificationDateKey, .fileSizeKey,
    ]

    /// Lists every song file; unchanged ones come straight from `previous`, the rest are read
    /// four at a time. `progress` gets (read, to read, songs so far) every 50 files.
    nonisolated private static func scan(
        roots: [LibraryRoot], previous: [String: Song], only: URL? = nil,
        progress: (@Sendable (Int, Int, [Song]) async -> Void)? = nil
    ) async -> [Song] {
        let (cached, work) = collect(roots: roots, previous: previous, only: only)
        var result = cached
        guard !work.isEmpty else { return result }

        var done = 0
        await withTaskGroup(of: Song.self) { group in
            var pending = work.makeIterator()
            for _ in 0..<4 {
                if let item = pending.next() { group.addTask { await readSong(item) } }
            }
            while let song = await group.next() {
                result.append(song)
                done += 1
                if let item = pending.next() { group.addTask { await readSong(item) } }
                if let progress, done % 50 == 0 || done == work.count {
                    await progress(done, work.count, result)
                }
            }
        }
        return result
    }

    /// Walks the roots (synchronously: directory enumerators can't be used in async code).
    nonisolated private static func collect(roots: [LibraryRoot], previous: [String: Song],
                                            only: URL?) -> (cached: [Song], work: [ScanItem]) {
        var cached: [Song] = []
        var work: [ScanItem] = []
        // The same file can be reachable twice (a linked folder containing another): keep the first.
        var seenPaths = Set<String>()

        for root in roots {
            let rootPath = root.url.resolvingSymlinksInPath().path
            for (folderPath, files) in filesByFolder(in: root.url, keys: resourceKeys) {
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
                let relativeFolder = folderPath.count > rootPath.count
                    ? String(folderPath.dropFirst(rootPath.count + 1)) : ""
                let folderKey = relativeFolder.isEmpty ? root.prefix : "\(root.prefix)/\(relativeFolder)"
                var folderCoverID: String??                 // stored once per folder, when needed

                for url in files where audioExtensions.contains(url.pathExtension.lowercased()) {
                    if let only, only.standardizedFileURL != url.standardizedFileURL { continue }
                    guard seenPaths.insert(url.resolvingSymlinksInPath().path).inserted else { continue }
                    let fileName = url.lastPathComponent
                    let key = relativeFolder.isEmpty ? "\(root.prefix)/\(fileName)"
                        : "\(root.prefix)/\(relativeFolder)/\(fileName)"
                    let values = try? url.resourceValues(forKeys: Set(resourceKeys))
                    let modified = values?.contentModificationDate ?? .distantPast
                    let lyrics = lyricsByName[url.deletingPathExtension().lastPathComponent.lowercased()]

                    // Unchanged file whose stored cover (if any) is still there: no need to read it.
                    if var song = previous[key], song.fileModified == modified,
                       song.artworkID.map(ArtworkStore.exists) ?? true {
                        song.lyricsURL = lyrics
                        cached.append(song)
                        continue
                    }
                    if folderCoverID == nil {
                        folderCoverID = .some(coverURL.flatMap { try? Data(contentsOf: $0) }
                            .flatMap(ArtworkStore.store))
                    }
                    work.append(ScanItem(
                        url: url, key: key, folderKey: folderKey, lyricsURL: lyrics,
                        folderCoverID: folderCoverID ?? nil, modified: modified,
                        added: values?.addedToDirectoryDate ?? values?.creationDate ?? .distantPast,
                        size: values?.fileSize ?? 0))
                }
            }
        }
        return (cached, work)
    }

    /// Reads one file's tags, length and cover.
    nonisolated private static func readSong(_ item: ScanItem) async -> Song {
        let url = item.url
        let tags = await TagIO.read(url)
        var song = Song(
            url: url, key: item.key,
            title: tags.title.isEmpty ? url.deletingPathExtension().lastPathComponent : tags.title,
            artist: tags.artist.isEmpty ? "Unknown Artist" : tags.artist,
            album: tags.album.isEmpty ? "Unknown Album" : tags.album,
            dateAdded: item.added)
        song.albumArtist = tags.albumArtist
        song.genre = tags.genre
        song.year = Int(tags.year.prefix(4)) ?? 0
        song.trackNumber = Int(tags.track) ?? 0
        song.discNumber = Int(tags.disc) ?? 0
        song.replayGainDb = tags.replayGainDb
        song.fileModified = item.modified
        song.fileSize = Int64(item.size)
        song.lyricsURL = item.lyricsURL
        song.folderKey = item.folderKey
        if let audio = try? AVAudioFile(forReading: url), audio.fileFormat.sampleRate > 0 {
            song.sampleRate = Int(audio.fileFormat.sampleRate)
            song.duration = Double(audio.length) / audio.fileFormat.sampleRate
        }
        if song.duration > 0 {
            song.bitrateKbps = Int(Double(song.fileSize) * 8 / song.duration / 1000)
        }
        // Stored on disk at Now Playing size; the song keeps only the id.
        song.artworkID = tags.artwork.flatMap(ArtworkStore.store) ?? item.folderCoverID
        return song
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
        let documents = self.documents
        // Copying big files takes a while: do it off the main thread.
        await Task.detached(priority: .userInitiated) { Self.copyIn(urls, to: documents) }.value
        await reload()
    }

    /// Copies files into Documents without overwriting a different song that has the same
    /// name ("Intro.mp3" becomes "Intro 2.mp3"); a file that's already there is skipped.
    /// A .lrc/.txt imported with a renamed song is renamed the same way so they still match.
    nonisolated private static func copyIn(_ urls: [URL], to documents: URL) {
        let fileManager = FileManager.default
        let audio = urls.filter { audioExtensions.contains($0.pathExtension.lowercased()) }
        let others = urls.filter { !audioExtensions.contains($0.pathExtension.lowercased()) }
        var renamed: [String: String] = [:]       // original base name (lowercased) -> new base name

        for url in audio + others {
            let hasAccess = url.startAccessingSecurityScopedResource()
            defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }

            let ext = url.pathExtension
            let base = url.deletingPathExtension().lastPathComponent
            let isAudio = audioExtensions.contains(ext.lowercased())
            var target = documents.appendingPathComponent(url.lastPathComponent)
            if !isAudio, let newBase = renamed[base.lowercased()] {
                target = documents.appendingPathComponent(newBase).appendingPathExtension(ext)
            }

            if fileManager.fileExists(atPath: target.path) {
                if isAudio {
                    if fileManager.contentsEqual(atPath: url.path, andPath: target.path) { continue }
                    var number = 2
                    repeat {
                        target = documents.appendingPathComponent("\(base) \(number)").appendingPathExtension(ext)
                        number += 1
                    } while fileManager.fileExists(atPath: target.path)
                    renamed[base.lowercased()] = target.deletingPathExtension().lastPathComponent
                } else {
                    try? fileManager.removeItem(at: target)     // newer lyrics replace older ones
                }
            }
            do {
                try fileManager.copyItem(at: url, to: target)
            } catch {
                print("Import failed for \(url.lastPathComponent): \(error)")
            }
        }
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
