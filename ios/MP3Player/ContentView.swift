import SwiftUI
import Combine
import PhotosUI
import UniformTypeIdentifiers

/// Root view: the library tabs (some can be hidden in Settings), mini player above the tab bar.
struct ContentView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var editor: SongEditor
    @Environment(\.scenePhase) private var scenePhase
    @State private var showNowPlaying = false
    @State private var coverItem: PhotosPickerItem?
    @AppStorage("selectedTab") private var selectedTab: AppTab = .songs

    private var visibleTabs: [AppTab] {
        AppTab.allCases.filter { !$0.hideable || !settings.settings.hiddenTabs.contains($0) }
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(visibleTabs) { tab in
                tabContent(tab)
                    .miniPlayer(showNowPlaying: $showNowPlaying)
                    .tabItem { Label(tab.rawValue, systemImage: tab.systemImage) }
                    .tag(tab)
            }
        }
        .preferredColorScheme(colorScheme)
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
        }
        .sheet(item: $editor.editing) { song in
            TagEditorView(song: song)
        }
        .photosPicker(isPresented: $editor.coverPickerShown, selection: $coverItem, matching: .images)
        .onChange(of: coverItem) { _, item in
            guard let item else { return }
            Task {
                await editor.applyCover(item)
                coverItem = nil
            }
        }
        .alert("Can't Play", isPresented: playerErrorShown) {
            Button("OK") { player.errorMessage = nil }
        } message: {
            Text(player.errorMessage ?? "")
        }
        .alert("Tags", isPresented: editorMessageShown) {
            Button("OK") { editor.message = nil }
        } message: {
            Text(editor.message ?? "")
        }
        .task {
            await library.reload()
            player.restoreIfNeeded(from: library.songs)     // resume last song (paused)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                // Rescan when returning (e.g. after adding files via Finder).
                Task {
                    await library.reload()
                    player.restoreIfNeeded(from: library.songs)
                }
            } else if phase == .background {
                player.saveState()                           // remember position
            }
        }
        .onChange(of: visibleTabs) { _, tabs in
            if !tabs.contains(selectedTab) { selectedTab = .songs }
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        switch tab {
        case .songs: SongsView()
        case .albums: AlbumsView()
        case .artists: ArtistsView()
        case .folders: FoldersView()
        case .playlists: PlaylistsView()
        case .equalizer: EqualizerView()
        case .wallpapers: WallpapersView()
        case .settings: SettingsView()
        }
    }

    private var colorScheme: ColorScheme? {
        switch settings.settings.appearance {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    private var playerErrorShown: Binding<Bool> {
        Binding(get: { player.errorMessage != nil },
                set: { if !$0 { player.errorMessage = nil } })
    }

    private var editorMessageShown: Binding<Bool> {
        Binding(get: { editor.message != nil },
                set: { if !$0 { editor.message = nil } })
    }
}

// MARK: - Songs tab

enum SongSort: String, CaseIterable, Identifiable {
    case title = "Title"
    case artist = "Artist"
    case album = "Album"
    case year = "Year"
    case recent = "Recently Added"
    var id: String { rawValue }
}

/// Same fields the desktop search checks: title, artist, album.
func searchSongs(_ songs: [Song], _ query: String) -> [Song] {
    let needle = query.trimmingCharacters(in: .whitespaces)
    guard !needle.isEmpty else { return songs }
    return songs.filter {
        $0.title.localizedCaseInsensitiveContains(needle)
            || $0.artist.localizedCaseInsensitiveContains(needle)
            || $0.album.localizedCaseInsensitiveContains(needle)
    }
}

func sortSongs(_ songs: [Song], by sort: SongSort) -> [Song] {
    func order(_ a: String, _ b: String) -> ComparisonResult { a.localizedStandardCompare(b) }
    func discTrack(_ song: Song) -> Int { song.discNumber * 1000 + song.trackNumber }

    return songs.sorted { a, b in
        switch sort {
        case .title:
            return order(a.title, b.title) == .orderedAscending
        case .artist:
            let byArtist = order(a.artist, b.artist)
            if byArtist != .orderedSame { return byArtist == .orderedAscending }
            let byAlbum = order(a.album, b.album)
            if byAlbum != .orderedSame { return byAlbum == .orderedAscending }
            if discTrack(a) != discTrack(b) { return discTrack(a) < discTrack(b) }
            return order(a.title, b.title) == .orderedAscending
        case .album:
            let byAlbum = order(a.album, b.album)
            if byAlbum != .orderedSame { return byAlbum == .orderedAscending }
            if discTrack(a) != discTrack(b) { return discTrack(a) < discTrack(b) }
            return order(a.title, b.title) == .orderedAscending
        case .year:
            if a.year != b.year { return a.year > b.year }
            return order(a.title, b.title) == .orderedAscending
        case .recent:
            return a.dateAdded > b.dateAdded
        }
    }
}

struct SongsView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var playlists: PlaylistManager

    @AppStorage("songSort") private var sort: SongSort = .title
    @State private var searchText = ""
    @State private var showImporter = false
    @State private var showNewPlaylist = false
    @State private var newPlaylistName = ""
    @State private var songForNewPlaylist: Song?

    private let importTypes: [UTType] = [
        .audio, UTType(filenameExtension: "lrc") ?? .plainText, .plainText,
    ]

    var body: some View {
        // Tapping a song queues exactly this list (searched + sorted).
        let songs = sortSongs(searchSongs(library.songs, searchText), by: sort)

        NavigationStack {
            Group {
                if library.songs.isEmpty {
                    ContentUnavailableView {
                        Label("No Songs", systemImage: "music.note")
                    } description: {
                        Text(library.isScanning ? "Looking for music…"
                             : "Tap + to import songs (and .lrc lyrics), or link a folder in the Folders tab.")
                    }
                } else {
                    List {
                        ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                            Button {
                                player.play(songs, startAt: index)
                            } label: {
                                SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                            }
                            .contextMenu {                       // long-press
                                SongMenu(song: song) { song in
                                    songForNewPlaylist = song
                                    showNewPlaylist = true
                                }
                            }
                        }
                        .onDelete { offsets in
                            let toDelete = offsets.map { songs[$0] }
                            Task { await library.delete(toDelete) }
                        }
                    }
                    .listStyle(.plain)
                    .refreshable { await library.reload() }
                    .overlay {
                        if songs.isEmpty && !searchText.isEmpty {
                            ContentUnavailableView.search(text: searchText)
                        }
                    }
                }
            }
            .navigationTitle("Songs")
            .searchable(text: $searchText, prompt: "Songs, artists, albums")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if library.isScanning { ProgressView() }
                    Menu {
                        Picker("Sort By", selection: $sort) {
                            ForEach(SongSort.allCases) { option in
                                Text(option.rawValue).tag(option)
                            }
                        }
                    } label: {
                        Image(systemName: "arrow.up.arrow.down")
                    }
                    Button { showImporter = true } label: { Image(systemName: "plus") }
                }
            }
            .fileImporter(
                isPresented: $showImporter,
                allowedContentTypes: importTypes,
                allowsMultipleSelection: true
            ) { result in
                if case .success(let urls) = result {
                    Task { await library.importFiles(urls) }
                }
            }
            .alert("New Playlist", isPresented: $showNewPlaylist) {
                TextField("Playlist name", text: $newPlaylistName)
                Button("Create") {
                    let id = playlists.create(name: newPlaylistName)
                    if let song = songForNewPlaylist { playlists.add([song], to: id) }
                    resetNewPlaylist()
                }
                Button("Cancel", role: .cancel) { resetNewPlaylist() }
            }
        }
    }

    private func resetNewPlaylist() {
        newPlaylistName = ""
        songForNewPlaylist = nil
    }
}

// MARK: - Shared pieces

struct SongRow: View {
    let song: Song
    let isCurrent: Bool

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(data: song.artworkData, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title)
                    .lineLimit(1)
                    .foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                Text(song.artist)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if song.lyricsURL != nil {
                Image(systemName: "quote.bubble")      // has a lyrics file
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if song.duration > 0 {
                Text(formatTime(song.duration))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}

struct ArtworkView: View {
    let data: Data?
    let size: CGFloat

    var body: some View {
        Group {
            if let data, let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Color.gray.opacity(0.2)
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

/// Puts the mini player at the bottom of a tab (stays visible inside pushed screens too).
struct MiniPlayerInset: ViewModifier {
    @EnvironmentObject private var player: PlayerManager
    @Binding var showNowPlaying: Bool

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            if player.currentSong != nil {
                MiniPlayer()
                    .contentShape(Rectangle())
                    .onTapGesture { showNowPlaying = true }   // open full-screen player
            }
        }
    }
}

extension View {
    func miniPlayer(showNowPlaying: Binding<Bool>) -> some View {
        modifier(MiniPlayerInset(showNowPlaying: showNowPlaying))
    }
}

struct MiniPlayer: View {
    @EnvironmentObject private var player: PlayerManager

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 16) {
                ArtworkView(data: player.currentSong?.artworkData, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.currentSong?.title ?? "").font(.headline).lineLimit(1)
                    Text(player.currentSong?.artist ?? "")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button { player.previous() } label: { Image(systemName: "backward.fill") }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title2)
                }
                Button { player.next() } label: { Image(systemName: "forward.fill") }
            }

            Slider(
                value: Binding(get: { player.currentTime }, set: { player.seek(to: $0) }),
                in: 0...max(player.duration, 1)
            )

            HStack {
                Text(formatTime(player.currentTime))
                Spacer()
                Text(formatTime(player.duration))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial)
    }
}
