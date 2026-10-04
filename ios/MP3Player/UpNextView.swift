import SwiftUI

/// The queue: what played, what's playing, songs you queued by hand (reorderable),
/// then the rest of the list playback was started from.
struct UpNextView: View {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var playlists: PlaylistManager
    @Environment(\.dismiss) private var dismiss
    @State private var showSave = false
    @State private var playlistName = ""

    private struct ListItem: Identifiable {
        let index: Int
        let song: Song
        var id: Int { index }
    }

    var body: some View {
        let _ = player.queueVersion                          // redraw when the queue changes
        let upcoming = player.upcomingFromList()?.map { ListItem(index: $0.index, song: $0.song) }
        let past = Array(player.history.dropLast().suffix(20))

        NavigationStack {
            List {
                if !past.isEmpty {
                    Section("History") {
                        ForEach(Array(past.enumerated()), id: \.offset) { _, song in
                            SongRow(song: song, isCurrent: false)
                                .opacity(0.6)
                        }
                    }
                }

                if let current = player.currentSong {
                    Section("Now Playing") {
                        SongRow(song: current, isCurrent: true)
                    }
                }

                if !player.manualQueue.isEmpty {
                    Section {
                        ForEach(player.manualQueue) { queued in
                            Button {
                                player.jumpToQueued(queued.id)
                            } label: {
                                SongRow(song: queued.song, isCurrent: false)
                            }
                            .buttonStyle(.plain)
                        }
                        .onDelete { player.removeFromQueue(at: $0) }
                        .onMove { player.moveInQueue(from: $0, to: $1) }
                    } header: {
                        HStack {
                            Text("Playing Next")
                            Spacer()
                            Button("Clear") { player.clearQueue() }
                                .font(.caption)
                                .textCase(nil)
                        }
                    }
                }

                Section {
                    if let upcoming {
                        if upcoming.isEmpty {
                            Text(player.repeatMode == .off ? "Nothing after this song." : "Repeating.")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(upcoming) { item in
                            Button {
                                player.jumpToListItem(item.index)
                            } label: {
                                SongRow(song: item.song, isCurrent: false)
                            }
                            .buttonStyle(.plain)
                        }
                        .onDelete { offsets in
                            // Highest index first, so the others don't shift.
                            for index in offsets.map({ upcoming[$0].index }).sorted(by: >) {
                                player.removeFromList(index)
                            }
                        }
                    } else {
                        Label("Random shuffle picks each next song when it's needed.", systemImage: "shuffle")
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(player.contextName.map { "Next From \($0)" } ?? "Next From Your List")
                } footer: {
                    if player.shuffleMode == .smart {
                        Text("Smart shuffle: every song plays once, in this order.")
                    }
                }
            }
            .navigationTitle("Up Next")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    if !player.manualQueue.isEmpty { EditButton() }
                    Menu {
                        Button("Save as Playlist…", systemImage: "music.note.list") { showSave = true }
                        Button("Clear Playing Next", systemImage: "xmark", role: .destructive) {
                            player.clearQueue()
                        }
                        .disabled(player.manualQueue.isEmpty)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .alert("Save Queue as Playlist", isPresented: $showSave) {
                TextField("Playlist name", text: $playlistName)
                Button("Save") {
                    let id = playlists.create(name: playlistName.isEmpty ? "Queue" : playlistName)
                    playlists.add(player.queueSnapshot(), to: id)
                    player.notice = "Saved the queue as a playlist."
                    playlistName = ""
                }
                Button("Cancel", role: .cancel) { playlistName = "" }
            }
        }
    }
}
