import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Playlists tab

struct PlaylistsView: View {
    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager

    @State private var showNewPlaylist = false
    @State private var newName = ""
    @State private var renameTarget: Playlist?
    @State private var renameText = ""
    @State private var newSmart: SmartPlaylist?
    @State private var showImporter = false
    @State private var coverTarget: UUID?
    @State private var showCoverPicker = false
    @State private var coverItem: PhotosPickerItem?

    var body: some View {
        // One lookup for every row, instead of one per row.
        let byKey = Dictionary(library.songs.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })

        TabStack {
            List {
                Section("Smart Lists") {
                    ForEach(SmartPlaylist.builtIn) { smart in
                        NavigationLink(value: SmartRoute(id: smart.id)) {
                            Label(smart.name, systemImage: smart.systemImage)
                        }
                    }
                    ForEach(playlists.smartPlaylists) { smart in
                        NavigationLink(value: SmartRoute(id: smart.id)) {
                            Label(smart.name, systemImage: "gearshape.2")
                        }
                    }
                    .onDelete { playlists.deleteSmart(at: $0) }
                }

                Section("Playlists") {
                    ForEach(playlists.playlists) { playlist in
                        let songs = playlist.songFiles.compactMap { byKey[$0] }
                        NavigationLink(value: playlist.id) {
                            HStack(spacing: 12) {
                                PlaylistCoverView(playlist: playlist, songs: songs)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(playlist.name).lineLimit(1)
                                    Text("\(songs.count) song\(songs.count == 1 ? "" : "s")")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .contextMenu {
                            Button("Rename", systemImage: "pencil") {
                                renameText = playlist.name
                                renameTarget = playlist
                            }
                            Button("Change Cover", systemImage: "photo") {
                                coverTarget = playlist.id
                                showCoverPicker = true
                            }
                            if playlist.coverFile != nil {
                                Button("Use Album Covers", systemImage: "square.grid.2x2") {
                                    playlists.removeCover(for: playlist.id)
                                }
                            }
                        }
                    }
                    .onDelete { playlists.delete(at: $0) }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Playlists")
            .navigationDestination(for: UUID.self) { id in
                PlaylistDetailView(playlistID: id)
            }
            .navigationDestination(for: SmartRoute.self) { route in
                SmartPlaylistDetailView(playlistID: route.id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("New Playlist", systemImage: "music.note.list") { showNewPlaylist = true }
                        Button("New Smart Playlist", systemImage: "gearshape.2") {
                            newSmart = SmartPlaylist(name: "")
                        }
                        Button("Import .m3u Playlist…", systemImage: "square.and.arrow.down") { showImporter = true }
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(item: $newSmart) { smart in
                SmartPlaylistEditor(playlist: smart) { playlists.saveSmart($0) }
            }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.m3uPlaylist, UTType(filenameExtension: "m3u8") ?? .m3uPlaylist],
                          allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                var messages: [String] = []
                for url in urls {
                    if let result = playlists.importM3U(url, library: library.songs) {
                        messages.append("\(url.deletingPathExtension().lastPathComponent): "
                                        + "\(result.matched) of \(result.total) songs found")
                    }
                }
                player.notice = messages.isEmpty ? "No songs found in that playlist." : messages.joined(separator: "\n")
            }
            .photosPicker(isPresented: $showCoverPicker, selection: $coverItem, matching: .images)
            .onChange(of: coverItem) { _, item in
                guard let item, let id = coverTarget else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        playlists.setCover(data, for: id)
                    }
                    coverItem = nil
                    coverTarget = nil
                }
            }
            .alert("New Playlist", isPresented: $showNewPlaylist) {
                TextField("Playlist name", text: $newName)
                Button("Create") {
                    playlists.create(name: newName)
                    newName = ""
                }
                Button("Cancel", role: .cancel) { newName = "" }
            }
            .alert("Rename Playlist",
                   isPresented: Binding(get: { renameTarget != nil },
                                        set: { if !$0 { renameTarget = nil } })) {
                TextField("Playlist name", text: $renameText)
                Button("Save") {
                    if let target = renameTarget { playlists.rename(target.id, to: renameText) }
                    renameTarget = nil
                }
                Button("Cancel", role: .cancel) { renameTarget = nil }
            }
        }
    }
}

/// Navigation value for smart playlists (UUID is already used by normal playlists).
struct SmartRoute: Hashable {
    let id: UUID
}

// MARK: - One playlist

struct PlaylistDetailView: View {
    let playlistID: UUID

    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @State private var showPicker = false
    @State private var showCoverPicker = false
    @State private var coverItem: PhotosPickerItem?

    private var playlist: Playlist? {
        playlists.playlists.first { $0.id == playlistID }
    }

    private var entries: [PlaylistEntry] {
        guard let playlist else { return [] }
        return playlists.entries(in: playlist, from: library.songs)
    }

    var body: some View {
        let entries = self.entries
        let songs = entries.compactMap(\.song)
        let name = playlist?.name ?? ""

        List {
            Section {
                VStack(spacing: 12) {
                    if let playlist {
                        PlaylistCoverView(playlist: playlist, songs: songs, size: 200)
                            .shadow(color: .black.opacity(0.2), radius: 12, y: 6)
                            .onTapGesture { showCoverPicker = true }
                    }
                    if !songs.isEmpty {
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
                    }
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
            }

            Section {
                ForEach(entries) { entry in
                    if let song = entry.song {
                        Button {
                            player.play(songs, startAt: songs.firstIndex { $0.id == song.id } ?? 0, from: name)
                        } label: {
                            SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                        }
                        .contextMenu { SongMenu(song: song) }
                    } else {
                        // The file was deleted, renamed or its folder unlinked.
                        HStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle")
                                .frame(width: 44, height: 44)
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text((entry.key as NSString).lastPathComponent).lineLimit(1)
                                Text("Unavailable").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .foregroundStyle(.secondary)
                    }
                }
                .onDelete { offsets in
                    var keys = entries.map(\.key)
                    keys.remove(atOffsets: offsets)
                    playlists.setKeys(keys, for: playlistID)
                }
                .onMove { from, to in
                    var keys = entries.map(\.key)
                    keys.move(fromOffsets: from, toOffset: to)
                    playlists.setKeys(keys, for: playlistID)
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("Empty Playlist",
                                       systemImage: "music.note.list",
                                       description: Text("Tap + to add songs."))
            }
        }
        .navigationTitle(name)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !entries.isEmpty { EditButton() }   // reorder / remove
                Button { showPicker = true } label: { Image(systemName: "plus") }
                Menu {
                    Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                        player.playNext(songs)
                    }
                    Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") {
                        player.addToQueue(songs)
                    }
                    Divider()
                    Button("Change Cover", systemImage: "photo") { showCoverPicker = true }
                    if playlist?.coverFile != nil {
                        Button("Use Album Covers", systemImage: "square.grid.2x2") {
                            playlists.removeCover(for: playlistID)
                        }
                    }
                    ShareLink(item: M3UExport(name: name, songs: songs),
                              preview: SharePreview("\(name).m3u8")) {
                        Label("Export .m3u Playlist", systemImage: "square.and.arrow.up")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showPicker) {
            SongPickerView(alreadyAdded: Set(playlist?.songFiles ?? [])) { picked in
                playlists.add(picked, to: playlistID)
            }
            .environmentObject(library)
        }
        .photosPicker(isPresented: $showCoverPicker, selection: $coverItem, matching: .images)
        .onChange(of: coverItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    playlists.setCover(data, for: playlistID)
                }
                coverItem = nil
            }
        }
    }
}

// MARK: - Multi-select song picker

struct SongPickerView: View {
    let alreadyAdded: Set<String>
    let onAdd: ([Song]) -> Void

    @EnvironmentObject private var library: LibraryManager
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<String> = []
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List(sortSongs(searchSongs(library.songs, query), by: .title)) { song in
                let added = alreadyAdded.contains(song.key)
                Button {
                    if selected.contains(song.id) { selected.remove(song.id) }
                    else { selected.insert(song.id) }
                } label: {
                    HStack {
                        SongRow(song: song, isCurrent: false)
                        Image(systemName: added || selected.contains(song.id)
                              ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(added ? Color.secondary : Color.accentColor)
                            .font(.title3)
                    }
                }
                .disabled(added)
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: "Songs, artists, albums")
            .navigationTitle("Add Songs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add (\(selected.count))") {
                        // Keep library order.
                        onAdd(library.songs.filter { selected.contains($0.id) })
                        dismiss()
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }
}
