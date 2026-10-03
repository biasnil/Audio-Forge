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
    @State private var showBackgroundWarning = false
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
        .alert("Background Audio Is Off", isPresented: $showBackgroundWarning) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Music will stop when you leave the app. In Xcode, add the Background Modes capability "
                 + "and tick \"Audio, AirPlay, and Picture in Picture\".")
        }
        .task {
            if !BackgroundAudio.isEnabled { showBackgroundWarning = true }
            await library.reload()
            player.restoreIfNeeded(from: library.songs)     // resume last song (paused)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                // Rescan when returning, if files may have changed (e.g. added via Finder).
                Task {
                    await library.reloadIfStale()
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
    /// Searched + sorted only when the library, search or sort changes, not on every redraw.
    @State private var songs: [Song] = []

    private let importTypes: [UTType] = [
        .audio, UTType(filenameExtension: "lrc") ?? .plainText, .plainText,
    ]

    private func refresh(_ all: [Song]) {
        songs = sortSongs(searchSongs(all, searchText), by: sort)
    }

    var body: some View {
        // Tapping a song queues exactly this list (searched + sorted).
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
            .onAppear { refresh(library.songs) }
            .onReceive(library.$songs.dropFirst()) { refresh($0) }
            .onChange(of: searchText) { _, _ in refresh(library.songs) }
            .onChange(of: sort) { _, _ in refresh(library.songs) }
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
            ArtworkView(data: song.artworkData, size: 44, cacheKey: song.key)
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
                ArtworkView(data: player.currentSong?.artworkData, size: 48, cacheKey: player.currentSong?.key)
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

            ProgressSlider(remaining: false, timeFont: .caption2.monospacedDigit())
        }
        .padding()
        .background(.regularMaterial)
    }
}

/// The seek bar and times. The only part of the players that redraws 5 times a second;
/// it seeks once when you let go instead of on every movement.
struct ProgressSlider: View {
    /// Right-hand label: "-remaining" instead of the total length.
    let remaining: Bool
    let timeFont: Font

    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var clock: PlaybackClock
    @State private var scrubbing: TimeInterval?

    var body: some View {
        let time = scrubbing ?? clock.time
        let duration = player.duration

        VStack(spacing: 4) {
            Slider(
                value: Binding(get: { min(time, max(duration, 1)) }, set: { scrubbing = $0 }),
                in: 0...max(duration, 1),
                onEditingChanged: { editing in
                    if !editing, let target = scrubbing {
                        player.seek(to: target)
                        scrubbing = nil
                    }
                }
            )
            HStack {
                Text(formatTime(time))
                Spacer()
                Text(remaining ? "-" + formatTime(max(duration - time, 0)) : formatTime(duration))
            }
            .font(timeFont)
            .foregroundStyle(.secondary)
        }
    }
}
