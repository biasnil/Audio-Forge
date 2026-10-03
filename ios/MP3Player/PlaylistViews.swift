import SwiftUI

// MARK: - Playlists tab

struct PlaylistsView: View {
    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager

    @State private var showNewPlaylist = false
    @State private var newName = ""
    @State private var renameTarget: Playlist?
    @State private var renameText = ""

    var body: some View {
        // One lookup set for every row, instead of one per row.
        let available = Set(library.songs.map(\.key))

        NavigationStack {
            List {
                ForEach(playlists.playlists) { playlist in
                    NavigationLink(value: playlist.id) {
                        HStack(spacing: 12) {
                            Image(systemName: "music.note.list")
                                .font(.title3)
                                .foregroundStyle(.secondary)
                                .frame(width: 44, height: 44)
                                .background(Color.gray.opacity(0.2))
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(playlist.name).lineLimit(1)
                                Text(songCount(playlist, available: available))
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
                    }
                }
                .onDelete { playlists.delete(at: $0) }
            }
            .listStyle(.plain)
            .overlay {
                if playlists.playlists.isEmpty {
                    ContentUnavailableView("No Playlists",
                                           systemImage: "music.note.list",
                                           description: Text("Tap + to create one."))
                }
            }
            .navigationTitle("Playlists")
            .navigationDestination(for: UUID.self) { id in
                PlaylistDetailView(playlistID: id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showNewPlaylist = true } label: { Image(systemName: "plus") }
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

    private func songCount(_ playlist: Playlist, available: Set<String>) -> String {
        let n = playlist.songFiles.filter(available.contains).count
        return "\(n) song\(n == 1 ? "" : "s")"
    }
}

// MARK: - One playlist

struct PlaylistDetailView: View {
    let playlistID: UUID

    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    @State private var showPicker = false

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

        List {
            if !songs.isEmpty {
                Section {
                    HStack(spacing: 12) {
                        Button {
                            player.play(songs, startAt: 0)
                        } label: {
                            Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        Button {
                            player.playShuffled(songs)
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
                ForEach(entries) { entry in
                    if let song = entry.song {
                        Button {
                            player.play(songs, startAt: songs.firstIndex { $0.id == song.id } ?? 0)
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
        .navigationTitle(playlist?.name ?? "")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !entries.isEmpty { EditButton() }   // reorder / remove
                Button { showPicker = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showPicker) {
            SongPickerView(alreadyAdded: Set(playlist?.songFiles ?? [])) { picked in
                playlists.add(picked, to: playlistID)
            }
            .environmentObject(library)
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
