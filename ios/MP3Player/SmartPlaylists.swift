import SwiftUI
import UniformTypeIdentifiers
import CoreTransferable

// MARK: - Model

/// What a smart-playlist rule looks at.
nonisolated enum SmartField: String, Codable, CaseIterable, Identifiable, Sendable {
    case title = "Title", artist = "Artist", album = "Album", genre = "Genre", year = "Year"
    case playCount = "Play Count", lastPlayedDays = "Last Played (days ago)", addedDays = "Added (days ago)"
    case favorite = "Loved", bpm = "BPM", key = "Key (e.g. 8A or Am)", minutes = "Length (minutes)"

    var id: String { rawValue }

    enum Kind { case text, number, bool }

    var kind: Kind {
        switch self {
        case .title, .artist, .album, .genre, .key: .text
        case .favorite: .bool
        default: .number
        }
    }

    var operators: [SmartOperator] {
        switch kind {
        case .text: [.contains, .equals, .isNot]
        case .number: [.equals, .greater, .less]
        case .bool: [.isTrue, .isFalse]
        }
    }
}

nonisolated enum SmartOperator: String, Codable, CaseIterable, Identifiable, Sendable {
    case contains = "contains", equals = "is", isNot = "is not", greater = "is more than", less = "is less than"
    case isTrue = "yes", isFalse = "no"
    var id: String { rawValue }
}

nonisolated struct SmartRule: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    var field: SmartField = .genre
    var op: SmartOperator = .contains
    var value = ""
}

nonisolated enum SmartSort: String, Codable, CaseIterable, Identifiable, Sendable {
    case title = "Title", mostPlayed = "Most Played", recentlyPlayed = "Recently Played"
    case recentlyAdded = "Recently Added", random = "Random", bpm = "BPM"
    var id: String { rawValue }
}

/// A playlist defined by rules ("genre contains Rock and year is more than 2010"); it updates itself.
nonisolated struct SmartPlaylist: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    var name: String
    var rules: [SmartRule] = [SmartRule()]
    var matchAll = true
    /// nil = no limit.
    var limit: Int?
    var sort: SmartSort = .title
    /// Built-in lists (Loved, Most Played…) can't be edited or deleted.
    var isBuiltIn = false

    /// The built-in smart lists shown at the top of Playlists.
    static let builtIn: [SmartPlaylist] = [
        SmartPlaylist(id: UUID(uuidString: "00000000-0000-0000-0000-00000000A001")!, name: "Loved",
                      rules: [SmartRule(field: .favorite, op: .isTrue)], sort: .title, isBuiltIn: true),
        SmartPlaylist(id: UUID(uuidString: "00000000-0000-0000-0000-00000000A002")!, name: "Most Played",
                      rules: [SmartRule(field: .playCount, op: .greater, value: "0")], limit: 100,
                      sort: .mostPlayed, isBuiltIn: true),
        SmartPlaylist(id: UUID(uuidString: "00000000-0000-0000-0000-00000000A003")!, name: "Recently Played",
                      rules: [SmartRule(field: .playCount, op: .greater, value: "0")], limit: 100,
                      sort: .recentlyPlayed, isBuiltIn: true),
        SmartPlaylist(id: UUID(uuidString: "00000000-0000-0000-0000-00000000A004")!, name: "Recently Added",
                      rules: [], limit: 100, sort: .recentlyAdded, isBuiltIn: true),
        SmartPlaylist(id: UUID(uuidString: "00000000-0000-0000-0000-00000000A005")!, name: "Never Played",
                      rules: [SmartRule(field: .playCount, op: .equals, value: "0")], sort: .recentlyAdded,
                      isBuiltIn: true),
    ]

    var systemImage: String {
        guard isBuiltIn else { return "gearshape.2" }
        return switch name {
        case "Loved": "heart.fill"
        case "Most Played": "flame"
        case "Recently Played": "clock.arrow.circlepath"
        case "Recently Added": "plus.circle"
        case "Never Played": "sparkles"
        default: "gearshape.2"
        }
    }

    /// The songs that match, sorted and limited.
    func songs(from library: [Song], stats: [String: SongStats], analysis: [String: SongAnalysis]) -> [Song] {
        let now = Date()
        let matching = library.filter { song in
            guard !rules.isEmpty else { return true }
            let results = rules.map { matches($0, song, stats[song.key] ?? SongStats(), analysis[song.key], now) }
            return matchAll ? results.allSatisfy { $0 } : results.contains(true)
        }
        var sorted: [Song]
        switch sort {
        case .title:
            sorted = matching.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        case .mostPlayed:
            sorted = matching.sorted { (stats[$0.key]?.playCount ?? 0) > (stats[$1.key]?.playCount ?? 0) }
        case .recentlyPlayed:
            sorted = matching.sorted {
                (stats[$0.key]?.lastPlayed ?? .distantPast) > (stats[$1.key]?.lastPlayed ?? .distantPast)
            }
        case .recentlyAdded:
            sorted = matching.sorted { $0.dateAdded > $1.dateAdded }
        case .random:
            sorted = matching.shuffled()
        case .bpm:
            sorted = matching.sorted { (analysis[$0.key]?.bpm ?? .infinity) < (analysis[$1.key]?.bpm ?? .infinity) }
        }
        if let limit, limit > 0, sorted.count > limit { sorted = Array(sorted.prefix(limit)) }
        return sorted
    }

    private func matches(_ rule: SmartRule, _ song: Song, _ stats: SongStats, _ analysis: SongAnalysis?,
                         _ now: Date) -> Bool {
        let value = rule.value.trimmingCharacters(in: .whitespaces)
        switch rule.field.kind {
        case .bool:
            return (rule.op == .isTrue) == stats.favorite
        case .text:
            let text: String
            switch rule.field {
            case .title: text = song.title
            case .artist: text = song.artist
            case .album: text = song.album
            case .genre: text = song.genre
            case .key:
                // Compare keys, not spellings: "8A" matches "Am".
                guard let key = analysis?.key else { return rule.op == .isNot }
                if let wanted = MusicKey.parse(value) {
                    return (key == wanted) != (rule.op == .isNot)
                }
                text = "\(key.camelot) \(key.shortName) \(key.name)"
            default: text = ""
            }
            switch rule.op {
            case .contains: return value.isEmpty || text.localizedCaseInsensitiveContains(value)
            case .equals: return text.caseInsensitiveCompare(value) == .orderedSame
            case .isNot: return text.caseInsensitiveCompare(value) != .orderedSame
            default: return false
            }
        case .number:
            guard let target = Double(value) else { return true }
            let number: Double?
            switch rule.field {
            case .year: number = song.year > 0 ? Double(song.year) : nil
            case .playCount: number = Double(stats.playCount)
            case .lastPlayedDays: number = stats.lastPlayed.map { now.timeIntervalSince($0) / 86_400 }
            case .addedDays: number = now.timeIntervalSince(song.dateAdded) / 86_400
            case .bpm: number = analysis?.bpm
            case .minutes: number = song.duration / 60
            default: number = nil
            }
            guard let number else { return false }
            switch rule.op {
            case .equals: return abs(number - target) < 0.5
            case .greater: return number > target
            case .less: return number < target
            default: return false
            }
        }
    }
}

// MARK: - Smart playlist screens

/// The songs of a smart playlist (built-in or your own), with Play / Shuffle.
struct SmartPlaylistDetailView: View {
    let playlistID: UUID

    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var songData: SongDataStore
    @State private var editing: SmartPlaylist?

    private var smart: SmartPlaylist? {
        SmartPlaylist.builtIn.first { $0.id == playlistID } ?? playlists.smartPlaylists.first { $0.id == playlistID }
    }

    var body: some View {
        let songs = smart?.songs(from: library.songs, stats: songData.stats, analysis: songData.analysis) ?? []
        let name = smart?.name ?? ""

        List {
            if !songs.isEmpty {
                Section {
                    HStack(spacing: 12) {
                        Button {
                            player.play(songs, startAt: 0, from: name)
                        } label: {
                            Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        Button {
                            player.playShuffled(songs, from: name)
                        } label: {
                            Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .listRowSeparator(.hidden)
                }
            }
            Section {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    Button {
                        player.play(songs, startAt: index, from: name)
                    } label: {
                        SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                    }
                    .contextMenu { SongMenu(song: song) }
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if songs.isEmpty {
                ContentUnavailableView("No Matching Songs", systemImage: smart?.systemImage ?? "gearshape.2",
                                       description: Text(smart?.isBuiltIn == true
                                                         ? "Songs appear here as you play and love them."
                                                         : "No songs match the rules yet."))
            }
        }
        .navigationTitle(name)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                        player.playNext(songs)
                    }
                    Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") {
                        player.addToQueue(songs)
                    }
                    if let smart, !smart.isBuiltIn {
                        Divider()
                        Button("Edit Rules", systemImage: "slider.horizontal.3") { editing = smart }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(item: $editing) { smart in
            SmartPlaylistEditor(playlist: smart) { playlists.saveSmart($0) }
        }
    }
}

/// Create or edit a smart playlist's rules.
struct SmartPlaylistEditor: View {
    @State var playlist: SmartPlaylist
    let onSave: (SmartPlaylist) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $playlist.name)
                    Picker("Match", selection: $playlist.matchAll) {
                        Text("All rules").tag(true)
                        Text("Any rule").tag(false)
                    }
                }
                Section("Rules") {
                    ForEach($playlist.rules) { $rule in
                        VStack(alignment: .leading, spacing: 8) {
                            Picker("Field", selection: $rule.field) {
                                ForEach(SmartField.allCases) { Text($0.rawValue).tag($0) }
                            }
                            .onChange(of: rule.field) { _, field in
                                if !field.operators.contains(rule.op) { rule.op = field.operators[0] }
                            }
                            Picker("Condition", selection: $rule.op) {
                                ForEach(rule.field.operators) { Text($0.rawValue).tag($0) }
                            }
                            if rule.field.kind != .bool {
                                TextField(rule.field.kind == .number ? "Number" : "Text", text: $rule.value)
                                    .keyboardType(rule.field.kind == .number ? .decimalPad : .default)
                                    .textFieldStyle(.roundedBorder)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .onDelete { playlist.rules.remove(atOffsets: $0) }
                    Button("Add Rule", systemImage: "plus") { playlist.rules.append(SmartRule()) }
                }
                Section {
                    Picker("Sort By", selection: $playlist.sort) {
                        ForEach(SmartSort.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Toggle("Limit", isOn: Binding(get: { playlist.limit != nil },
                                                  set: { playlist.limit = $0 ? 25 : nil }))
                    if let limit = playlist.limit {
                        Stepper("\(limit) songs", value: Binding(get: { limit }, set: { playlist.limit = $0 }),
                                in: 5...1000, step: 5)
                    }
                } footer: {
                    Text("Smart playlists update by themselves as your library, play counts and loved songs change.")
                }
            }
            .navigationTitle(playlist.name.isEmpty ? "Smart Playlist" : playlist.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if playlist.name.trimmingCharacters(in: .whitespaces).isEmpty {
                            playlist.name = "Smart Playlist"
                        }
                        onSave(playlist)
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Playlist covers

/// A playlist's picture: your own image, or a grid of its first four album covers.
struct PlaylistCoverView: View {
    let playlist: Playlist
    let songs: [Song]
    var size: CGFloat = 44

    /// Up to four different covers, in playlist order.
    private var firstCovers: [String] {
        var seen = Set<String>()
        var result: [String] = []
        for id in songs.lazy.compactMap(\.artworkID) where seen.insert(id).inserted {
            result.append(id)
            if result.count == 4 { break }
        }
        return result
    }

    var body: some View {
        Group {
            if let file = playlist.coverFile {
                CoverFileImage(url: PlaylistManager.coverURL(file), size: size)
            } else {
                let covers = firstCovers
                if covers.count >= 4 {
                    let half = size / 2
                    VStack(spacing: 0) {
                        HStack(spacing: 0) {
                            ArtworkView(artworkID: covers[0], size: half, cornerRadius: 0)
                            ArtworkView(artworkID: covers[1], size: half, cornerRadius: 0)
                        }
                        HStack(spacing: 0) {
                            ArtworkView(artworkID: covers[2], size: half, cornerRadius: 0)
                            ArtworkView(artworkID: covers[3], size: half, cornerRadius: 0)
                        }
                    }
                } else if let first = covers.first {
                    ArtworkView(artworkID: first, size: size, cornerRadius: 0)
                } else {
                    Image(systemName: "music.note.list")
                        .font(size > 60 ? .largeTitle : .title3)
                        .foregroundStyle(.secondary)
                        .frame(width: size, height: size)
                        .background(Color.gray.opacity(0.2))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size > 60 ? 10 : 6))
    }
}

/// A custom playlist cover, decoded in the background.
private struct CoverFileImage: View {
    let url: URL
    let size: CGFloat
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color.gray.opacity(0.2)
            }
        }
        .task(id: url) {
            let pixels = Int(size * 3)
            image = await Task.detached(priority: .userInitiated) {
                (try? Data(contentsOf: url)).flatMap { ImageTools.downsample($0, maxPixels: pixels) }
                    .map { UIImage(cgImage: $0) }
            }.value
        }
    }
}

// MARK: - .m3u export

/// A playlist written as an .m3u8 file when it's shared (paths relative to the music folder,
/// so the desktop and Android apps can match songs by file name).
nonisolated struct M3UExport: Transferable, Sendable {
    let name: String
    let songs: [Song]

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .m3uPlaylist) { export in
            SentTransferredFile(try export.write())
        }
    }

    func write() throws -> URL {
        var text = "#EXTM3U\n"
        for song in songs {
            text += "#EXTINF:\(Int(song.duration.rounded())),\(song.artist) - \(song.title)\n"
            // The path inside its root folder ("docs/Rock/a.mp3" → "Rock/a.mp3").
            let path = song.key.split(separator: "/", maxSplits: 1).last.map(String.init) ?? song.fileName
            text += path + "\n"
        }
        let safeName = name.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safeName).m3u8")
        try text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}
