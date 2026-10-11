import SwiftUI
import Combine
import PhotosUI

/// Edit tags / Change cover, available from any song's long-press menu.
@MainActor
final class SongEditor: ObservableObject {
    /// The song whose tag editor sheet is open.
    @Published var editing: Song?
    /// The song a cover is being picked for (shows the photo picker).
    @Published var coverTarget: Song?
    @Published var coverPickerShown = false
    @Published var message: String?
    /// Songs waiting for the user to confirm deleting them.
    @Published var pendingDelete: [Song]?

    private let library: LibraryManager
    private let player: PlayerManager

    init(library: LibraryManager, player: PlayerManager) {
        self.library = library
        self.player = player
    }

    func changeCover(_ song: Song) {
        coverTarget = song
        coverPickerShown = true
    }

    /// Writes the picked photo into `song` as its cover.
    func applyCover(_ item: PhotosPickerItem?, to song: Song?) async {
        guard let item, let song else { return }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let cover = await Task.detached(operation: { TagIO.prepareCover(data) }).value else {
            message = "That image couldn't be used."
            return
        }
        var fields = await Self.readTags(song.url)
        fields.artwork = cover
        if await save(song, fields: fields, artworkChanged: true) { message = "Cover changed." }
    }

    /// Writes the tags into the file, then reloads it in the library and the player.
    /// Returns false (and sets `message`) if it failed.
    func save(_ song: Song, fields: TagFields, artworkChanged: Bool) async -> Bool {
        guard TagIO.canWrite(song.url) else {
            message = TagError.unsupported(song.url.pathExtension.lowercased()).errorDescription
            return false
        }
        let hold = player.releaseFileForEdit(song)
        var failure: String?
        do {
            // Rewriting a big file takes a moment: keep it off the main thread.
            try await Task.detached(priority: .userInitiated) {
                try await TagIO.write(fields, to: song.url, artworkChanged: artworkChanged)
            }.value
        } catch {
            failure = error.localizedDescription
        }
        LyricsFinder.clearCache(for: song)
        let updated = await library.refresh(song) ?? song
        player.resumeAfterFileEdit(updated, hold: hold)
        if let failure {
            message = failure
            return false
        }
        return true
    }

    /// Reads a file's tags off the main thread.
    nonisolated static func readTags(_ url: URL) async -> TagFields {
        await Task.detached(priority: .userInitiated) { await TagIO.read(url) }.value
    }

    /// What the delete confirmation says: linked-folder songs are the user's original files.
    static func deleteWarning(for songs: [Song]) -> String {
        let linked = songs.contains { $0.key.hasPrefix("link:") }
        return linked
            ? "This deletes the original file from your linked folder (and from iCloud Drive if it's "
                + "there, on all your devices). It can't be undone."
            : "The file is removed from the app. It can't be undone."
    }
}

/// The long-press menu items for a song: queue, love, playlists, tags, cover, delete.
struct SongMenu: View {
    let song: Song
    var onNewPlaylist: ((Song) -> Void)?

    @EnvironmentObject private var playlists: PlaylistManager
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var editor: SongEditor
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var songData: SongDataStore

    var body: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext([song]) }
        Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") { player.addToQueue([song]) }
        let loved = songData.isFavorite(song.key)
        Button(loved ? "Unlove" : "Love", systemImage: loved ? "heart.slash" : "heart") {
            songData.toggleFavorite(song.key)
        }
        Divider()

        if let onNewPlaylist {
            Menu {
                ForEach(playlists.playlists) { playlist in
                    Button(playlist.name) { playlists.add([song], to: playlist.id) }
                }
                if !playlists.playlists.isEmpty { Divider() }
                Button("New Playlist…", systemImage: "plus") { onNewPlaylist(song) }
            } label: {
                Label("Add to Playlist", systemImage: "text.badge.plus")
            }
        }

        Button("Edit Tags", systemImage: "tag") { editor.editing = song }
        Button("Change Cover", systemImage: "photo") { editor.changeCover(song) }

        Button(role: .destructive) {
            editor.pendingDelete = [song]            // asks first, see ContentView
        } label: {
            Label("Delete Song", systemImage: "trash")
        }
    }
}

/// Every tag, written into the file on Save (MP3, FLAC, M4A).
struct TagEditorView: View {
    let song: Song

    @EnvironmentObject private var songData: SongDataStore

    @EnvironmentObject private var editor: SongEditor
    @Environment(\.dismiss) private var dismiss
    @State private var fields = TagFields()
    @State private var original = TagFields()
    @State private var loaded = false
    @State private var saving = false
    @State private var photo: PhotosPickerItem?
    @State private var artworkChanged = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 8) {
                            ArtworkView(data: fields.artwork, size: 160)
                            HStack {
                                PhotosPicker(selection: $photo, matching: .images) {
                                    Text("Change Cover")
                                }
                                if fields.artwork != nil {
                                    Button("Remove", role: .destructive) {
                                        fields.artwork = nil
                                        artworkChanged = true
                                    }
                                }
                            }
                            .buttonStyle(.borderless)
                        }
                        Spacer()
                    }
                }
                .listRowBackground(Color.clear)

                Section("Song") {
                    field("Title", $fields.title)
                    field("Artist", $fields.artist)
                    field("Album", $fields.album)
                    field("Album Artist", $fields.albumArtist)
                    field("Genre", $fields.genre)
                    field("Year", $fields.year, numeric: true)
                }
                Section("Numbers") {
                    numberPair("Track", $fields.track, $fields.trackTotal)
                    numberPair("Disc", $fields.disc, $fields.discTotal)
                    field("BPM", $fields.bpm, numeric: true)
                    field("Key", $fields.key)
                    if let analysis = songData.analysis(for: song), analysis.bpm != nil || analysis.key != nil {
                        detectedRow(analysis)
                    }
                }
                Section("More") {
                    field("Composer", $fields.composer)
                    LabeledContent("Comment") {
                        TextField("Comment", text: $fields.comment, axis: .vertical)
                            .lineLimit(1...4)
                            .multilineTextAlignment(.trailing)
                    }
                }
                Section {
                    LabeledContent("File", value: song.fileName)
                    if song.duration > 0 {
                        LabeledContent("Length", value: formatTime(song.duration))
                    }
                    if song.bitrateKbps > 0 {
                        LabeledContent("Bitrate", value: "\(song.bitrateKbps) kbps")
                    }
                    if song.sampleRate > 0 {
                        LabeledContent("Sample Rate", value: "\(song.sampleRate) Hz")
                    }
                    if let gain = fields.replayGainDb {
                        LabeledContent("ReplayGain", value: String(format: "%+.2f dB", gain))
                    }
                }
                if !TagIO.canWrite(song.url) {
                    Section {
                        Text(TagError.unsupported(song.url.pathExtension.lowercased()).errorDescription ?? "")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(!loaded || saving)
            .navigationTitle("Edit Tags")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button("Save") { save() }
                            .disabled(!TagIO.canWrite(song.url) || (fields == original && !artworkChanged))
                    }
                }
            }
            .task {
                guard !loaded else { return }
                let read = await SongEditor.readTags(song.url)
                fields = read
                original = read
                loaded = true
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let cover = TagIO.prepareCover(data) {
                        fields.artwork = cover
                        artworkChanged = true
                    }
                    photo = nil
                }
            }
        }
    }

    private func field(_ title: String, _ text: Binding<String>, numeric: Bool = false) -> some View {
        LabeledContent(title) {
            TextField(title, text: text)
                .multilineTextAlignment(.trailing)
                .keyboardType(numeric ? .numberPad : .default)
                .autocorrectionDisabled(numeric)
        }
    }

    /// What the analysis found, with a button to write it into the BPM and Key fields.
    private func detectedRow(_ analysis: SongAnalysis) -> some View {
        let bpm = analysis.bpm.map { String(Int($0.rounded())) }
        let key = analysis.key
        let summary = [bpm.map { "\($0) BPM" }, key.map { "\($0.name) (\($0.camelot))" }]
            .compactMap { $0 }.joined(separator: " · ")
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Detected").font(.caption).foregroundStyle(.secondary)
                Text(summary).font(.subheadline)
            }
            Spacer()
            Button("Use") {
                if let bpm { fields.bpm = bpm }
                if let key { fields.key = key.tagValue }
            }
            .buttonStyle(.bordered)
            .disabled(fields.bpm == (bpm ?? fields.bpm) && fields.key == (key?.tagValue ?? fields.key))
        }
    }

    private func numberPair(_ title: String, _ number: Binding<String>, _ total: Binding<String>) -> some View {
        LabeledContent(title) {
            HStack(spacing: 6) {
                TextField("#", text: number)
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numberPad)
                Text("of").foregroundStyle(.secondary)
                TextField("Total", text: total)
                    .frame(maxWidth: 60)
                    .keyboardType(.numberPad)
            }
        }
    }

    private func save() {
        saving = true
        Task {
            let ok = await editor.save(song, fields: fields, artworkChanged: artworkChanged)
            saving = false
            if ok { dismiss() }
        }
    }
}

func formatTime(_ time: TimeInterval) -> String {
    let seconds = Int(time.isFinite ? max(time, 0) : 0)
    return seconds >= 3600
        ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String(format: "%d:%02d", seconds / 60, seconds % 60)
}
