import SwiftUI
import Combine
import UniformTypeIdentifiers

/// Root view: Songs / Albums / Artists / Playlists tabs, mini player above the tab bar.
struct ContentView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @Environment(\.scenePhase) private var scenePhase
    @State private var showNowPlaying = false

    // 0.25s keeps synced lyrics responsive.
    private let ticker = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            SongsView()
                .miniPlayer(showNowPlaying: $showNowPlaying)
                .tabItem { Label("Songs", systemImage: "music.note") }

            AlbumsView()
                .miniPlayer(showNowPlaying: $showNowPlaying)
                .tabItem { Label("Albums", systemImage: "square.stack") }

            ArtistsView()
                .miniPlayer(showNowPlaying: $showNowPlaying)
                .tabItem { Label("Artists", systemImage: "music.mic") }

            PlaylistsView()
                .miniPlayer(showNowPlaying: $showNowPlaying)
                .tabItem { Label("Playlists", systemImage: "music.note.list") }
        }
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
                .environmentObject(player)
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
        .onReceive(ticker) { _ in player.refreshTime() }
    }
}

// MARK: - Songs tab

enum SongSort: String, CaseIterable, Identifiable {
    case title = "Title"
    case artist = "Artist"
    case recent = "Recently Added"
    var id: String { rawValue }
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

    private let importTypes: [UTType] = [.audio, UTType(filenameExtension: "lrc") ?? .plainText]

    /// Search + sort applied. Tapping a song queues exactly this list.
    private var visibleSongs: [Song] {
        let query = searchText.trimmingCharacters(in: .whitespaces)
        var result = query.isEmpty ? library.songs : library.songs.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || $0.artist.localizedCaseInsensitiveContains(query)
                || $0.album.localizedCaseInsensitiveContains(query)
        }
        switch sort {
        case .title:
            result.sort { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        case .artist:
            result.sort { a, b in
                let byArtist = a.artist.localizedStandardCompare(b.artist)
                if byArtist != .orderedSame { return byArtist == .orderedAscending }
                return a.title.localizedStandardCompare(b.title) == .orderedAscending
            }
        case .recent:
            result.sort { $0.dateAdded > $1.dateAdded }
        }
        return result
    }

    var body: some View {
        let songs = visibleSongs

        NavigationStack {
            Group {
                if library.songs.isEmpty {
                    ContentUnavailableView(
                        "No Songs",
                        systemImage: "music.note",
                        description: Text("Tap + to import MP3 files (and .lrc lyrics).")
                    )
                } else {
                    List {
                        ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                            Button {
                                player.play(songs, startAt: index)
                            } label: {
                                SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                            }
                            .contextMenu { songMenu(for: song) }   // long-press
                        }
                        .onDelete { offsets in
                            let toDelete = offsets.map { songs[$0] }
                            Task { await library.delete(toDelete) }
                        }
                    }
                    .listStyle(.plain)
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

    @ViewBuilder
    private func songMenu(for song: Song) -> some View {
        Menu {
            ForEach(playlists.playlists) { playlist in
                Button(playlist.name) { playlists.add([song], to: playlist.id) }
            }
            if !playlists.playlists.isEmpty { Divider() }
            Button("New Playlist…", systemImage: "plus") {
                songForNewPlaylist = song
                showNewPlaylist = true
            }
        } label: {
            Label("Add to Playlist", systemImage: "text.badge.plus")
        }

        Button(role: .destructive) {
            Task { await library.delete([song]) }
        } label: {
            Label("Delete Song", systemImage: "trash")
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
                Image(systemName: "quote.bubble")      // has synced lyrics
                    .font(.caption)
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
                Text(format(player.currentTime))
                Spacer()
                Text(format(player.duration))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .padding()
        .background(.regularMaterial)
    }

    private func format(_ time: TimeInterval) -> String {
        let seconds = Int(time)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
