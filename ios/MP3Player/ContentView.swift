import SwiftUI
import Combine
import PhotosUI
import UniformTypeIdentifiers

/// Root view: the library tabs (some can be hidden in Settings), mini player above the tab bar.
struct ContentView: View {
    /// Plain references, deliberately not observed: the root (and with it the whole TabView)
    /// shouldn't redraw on every player, library or settings change. Child views observe
    /// what they show.
    let settings: SettingsStore
    let library: LibraryManager
    let player: PlayerManager
    let songData: SongDataStore
    @EnvironmentObject private var editor: SongEditor
    @Environment(\.scenePhase) private var scenePhase
    @State private var showNowPlaying = false
    @State private var coverItem: PhotosPickerItem?
    @State private var showBackgroundWarning = false
    @State private var appearance: Appearance = .system
    @State private var hiddenTabs: Set<AppTab> = []
    @AppStorage("selectedTabName") private var selectedTab = AppTab.songs.rawValue

    private static let moreTag = "More"

    private var visibleTabs: [AppTab] {
        AppTab.allCases.filter { !$0.hideable || !hiddenTabs.contains($0) }
    }

    /// The tab bar fits 5. With more, the 5th is our own "More" list (instead of iOS's,
    /// which wraps each tab's navigation in a second one and shows double title bars).
    private var barTabs: [AppTab] { visibleTabs.count > 5 ? Array(visibleTabs.prefix(4)) : visibleTabs }
    private var moreTabs: [AppTab] { visibleTabs.count > 5 ? Array(visibleTabs.dropFirst(4)) : [] }

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(barTabs) { tab in
                tabContent(tab)
                    .miniPlayer(showNowPlaying: $showNowPlaying)
                    .tabItem { Label(tab.rawValue, systemImage: tab.systemImage) }
                    .tag(tab.rawValue)
            }
            if !moreTabs.isEmpty {
                MoreTabView(tabs: moreTabs) { tabContent($0) }
                    .miniPlayer(showNowPlaying: $showNowPlaying)
                    .tabItem { Label("More", systemImage: "ellipsis") }
                    .tag(Self.moreTag)
            }
        }
        .preferredColorScheme(colorScheme)
        .onReceive(settings.$settings.map(\.appearance).removeDuplicates()) { appearance = $0 }
        .onReceive(settings.$settings.map(\.hiddenTabs).removeDuplicates()) { hiddenTabs = $0 }
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
                await editor.applyCover(item, to: editor.coverTarget)
                editor.coverTarget = nil
                coverItem = nil
            }
        }
        .confirmationDialog(deleteTitle, isPresented: deleteShown, titleVisibility: .visible,
                            presenting: editor.pendingDelete) { songs in
            Button("Delete", role: .destructive) {
                Task { await library.delete(songs) }
            }
        } message: { songs in
            Text(SongEditor.deleteWarning(for: songs))
        }
        .modifier(PlaybackMessages())
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
                // Rescan when returning (e.g. files added via Finder or to a linked folder).
                Task {
                    await library.reloadIfStale()
                    player.restoreIfNeeded(from: library.songs)
                }
            } else if phase == .background {
                player.saveState()                           // remember position
                settings.saveNow()
                songData.saveNow()
            }
        }
        .onChange(of: visibleTabs) { _, _ in
            let tags = barTabs.map(\.rawValue) + (moreTabs.isEmpty ? [] : [Self.moreTag])
            if !tags.contains(selectedTab) { selectedTab = AppTab.songs.rawValue }
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        switch tab {
        case .songs: SongsView()
        case .albums: AlbumsView()
        case .artists: ArtistsView()
        case .genres: GenresView()
        case .folders: FoldersView()
        case .playlists: PlaylistsView()
        case .equalizer: EqualizerView()
        case .wallpapers: WallpapersView()
        case .settings: SettingsView()
        }
    }

    private var colorScheme: ColorScheme? {
        switch appearance {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    private var deleteTitle: String {
        let count = editor.pendingDelete?.count ?? 0
        return count == 1 ? "Delete \(editor.pendingDelete?.first?.title ?? "song")?" : "Delete \(count) songs?"
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { editor.pendingDelete != nil },
                set: { if !$0 { editor.pendingDelete = nil } })
    }
}

/// The "More" tab: the tabs that don't fit in the bar, in one navigation stack.
struct MoreTabView<Content: View>: View {
    let tabs: [AppTab]
    @ViewBuilder let content: (AppTab) -> Content

    var body: some View {
        NavigationStack {
            List(tabs) { tab in
                NavigationLink(value: tab) {
                    Label(tab.rawValue, systemImage: tab.systemImage)
                }
            }
            .navigationTitle("More")
            .navigationDestination(for: AppTab.self) { tab in
                content(tab).environment(\.inNavigationStack, true)
            }
        }
    }
}

extension EnvironmentValues {
    /// True when a tab is shown inside the More tab's navigation stack.
    @Entry var inNavigationStack = false
}

/// A tab's navigation stack, unless it's already inside one (the More tab).
struct TabStack<Content: View>: View {
    @Environment(\.inNavigationStack) private var inNavigationStack
    @ViewBuilder let content: () -> Content

    var body: some View {
        if inNavigationStack {
            content()
        } else {
            NavigationStack { content() }
        }
    }
}

/// Playback and tag messages: the "Can't Play" / "Tags" alerts and the "Skipped …" banner.
/// Used on the root and on Now Playing (a sheet covers the root's alerts).
struct PlaybackMessages: ViewModifier {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var editor: SongEditor

    func body(content: Content) -> some View {
        content
            .alert("Can't Play", isPresented: Binding(get: { player.errorMessage != nil },
                                                      set: { if !$0 { player.errorMessage = nil } })) {
                Button("OK") { player.errorMessage = nil }
            } message: {
                Text(player.errorMessage ?? "")
            }
            .alert("Tags", isPresented: Binding(get: { editor.message != nil },
                                                set: { if !$0 { editor.message = nil } })) {
                Button("OK") { editor.message = nil }
            } message: {
                Text(editor.message ?? "")
            }
            .overlay(alignment: .top) {
                if let notice = player.notice {
                    Text(notice)
                        .font(.footnote.weight(.medium))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, 8)
                        .padding(.horizontal)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onTapGesture { player.notice = nil }
                        .task(id: notice) {
                            try? await Task.sleep(for: .seconds(4))
                            if player.notice == notice { withAnimation { player.notice = nil } }
                        }
                }
            }
            .animation(.default, value: player.notice)
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
    @EnvironmentObject private var editor: SongEditor

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
        TabStack {
            Group {
                if library.songs.isEmpty {
                    ContentUnavailableView {
                        Label("No Songs", systemImage: "music.note")
                    } description: {
                        Text(library.isScanning ? "Looking for music…"
                             : "Tap + to import songs (and .lrc lyrics), or link a folder in the Folders tab.")
                    } actions: {
                        if let progress = library.scanProgress {
                            ScanProgressView(progress: progress)
                        }
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
                            editor.pendingDelete = offsets.map { songs[$0] }   // asks first
                        }
                    }
                    .listStyle(.plain)
                    .refreshable { await library.reload() }
                    .safeAreaInset(edge: .top) {
                        // First scan: songs appear as they're read; show how far along it is.
                        if let progress = library.scanProgress {
                            ScanProgressView(progress: progress)
                                .padding(.horizontal)
                                .padding(.vertical, 6)
                                .background(.bar)
                        }
                    }
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

/// "Reading songs… 120 of 2,000" with a bar.
struct ScanProgressView: View {
    let progress: (done: Int, total: Int)

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Reading songs… \(progress.done) of \(progress.total)")
                .font(.caption)
                .foregroundStyle(.secondary)
            ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
        }
        .frame(maxWidth: 320)
    }
}

struct SongRow: View {
    let song: Song
    let isCurrent: Bool

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(artworkID: song.artworkID, size: 44)
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
                ArtworkView(artworkID: player.currentSong?.artworkID, size: 48)
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
