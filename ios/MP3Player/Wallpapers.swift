import SwiftUI
import AVFoundation
import PhotosUI
import UniformTypeIdentifiers
import CoreTransferable

/// Video wallpaper files, copied into Application Support/Wallpapers.
enum WallpaperFiles {
    static var folder: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Wallpapers", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func url(for file: String) -> URL {
        folder.appendingPathComponent(file)
    }

    /// Copies a video in and returns its stored file name.
    static func importVideo(from source: URL) throws -> String {
        let hasAccess = source.startAccessingSecurityScopedResource()
        defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
        let ext = source.pathExtension.isEmpty ? "mov" : source.pathExtension
        let base = source.deletingPathExtension().lastPathComponent
        var name = "\(base).\(ext)"
        var counter = 2
        while FileManager.default.fileExists(atPath: url(for: name).path) {
            name = "\(base) \(counter).\(ext)"
            counter += 1
        }
        try FileManager.default.copyItem(at: source, to: url(for: name))
        return name
    }

    /// The first assignment containing the song wins, otherwise the global video; nil = none.
    static func resolve(songKey: String, in settings: AppSettings) -> URL? {
        guard settings.videoWallpaperEnabled else { return nil }
        let file = settings.wallpapers.first { $0.songKeys.contains(songKey) }?.videoFile
            ?? (settings.globalWallpaperFile.isEmpty ? nil : settings.globalWallpaperFile)
        guard let file else { return nil }
        let url = url(for: file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Deletes a video file once nothing uses it (call after the settings change).
    static func deleteIfUnused(_ file: String, settings: AppSettings) {
        guard !file.isEmpty, settings.globalWallpaperFile != file,
              !settings.wallpapers.contains(where: { $0.videoFile == file }) else { return }
        try? FileManager.default.removeItem(at: url(for: file))
    }
}

/// A video picked from Photos, received as a file.
nonisolated struct PickedVideo: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { video in
            SentTransferredFile(video.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedVideo(url: copy)
        }
    }
}

// MARK: - Wallpapers tab

/// One global video plus per-song videos behind Now Playing (first matching assignment wins).
struct WallpapersView: View {
    @EnvironmentObject private var settings: SettingsStore

    /// What a newly picked video is for.
    private enum Target: Equatable {
        case global
        case newEntry
    }

    @State private var target: Target?
    @State private var showFiles = false
    @State private var showPhotos = false
    @State private var photoItem: PhotosPickerItem?
    @State private var pendingVideo: String?          // new per-song video waiting for its songs
    @State private var editingEntry: WallpaperEntry?
    @State private var failed = false

    var body: some View {
        let s = settings.settings

        NavigationStack {
            Form {
                Section {
                    LabeledContent("Video", value: s.globalWallpaperFile.isEmpty ? "None" : s.globalWallpaperFile)
                    chooseMenu("Choose Video…", for: .global)
                    if !s.globalWallpaperFile.isEmpty {
                        Button("Clear", role: .destructive) {
                            let old = s.globalWallpaperFile
                            settings.update { $0.globalWallpaperFile = "" }
                            WallpaperFiles.deleteIfUnused(old, settings: settings.settings)
                        }
                    }
                } header: {
                    Text("All Songs")
                } footer: {
                    Text("Plays behind Now Playing for songs without a video of their own.")
                }

                Section {
                    ForEach(s.wallpapers) { entry in
                        Button {
                            editingEntry = entry
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.videoFile).foregroundStyle(Color.primary).lineLimit(1)
                                Text("\(entry.songKeys.count) song\(entry.songKeys.count == 1 ? "" : "s") · Tap to edit")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in
                        let removed = offsets.map { s.wallpapers[$0] }
                        settings.update { $0.wallpapers.remove(atOffsets: offsets) }
                        removed.forEach { WallpaperFiles.deleteIfUnused($0.videoFile, settings: settings.settings) }
                    }
                    chooseMenu("Add Video for Songs…", for: .newEntry)
                } header: {
                    Text("Per Song")
                } footer: {
                    Text("Videos are muted and loop. Turn wallpapers off or change their opacity in Settings.")
                }
            }
            .navigationTitle("Wallpapers")
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.movie]) { result in
                if case .success(let url) = result { addVideo(from: url, deleteSource: false) }
            }
            .photosPicker(isPresented: $showPhotos, selection: $photoItem, matching: .videos)
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let video = try? await item.loadTransferable(type: PickedVideo.self) {
                        addVideo(from: video.url, deleteSource: true)
                    } else {
                        failed = true
                    }
                    photoItem = nil
                }
            }
            .sheet(item: Binding(get: { pendingVideo.map(PendingVideo.init) },
                                 set: { new in
                                     // Swiped away without choosing songs: drop the copied video.
                                     if new == nil, let file = pendingVideo {
                                         WallpaperFiles.deleteIfUnused(file, settings: settings.settings)
                                     }
                                     pendingVideo = new?.file
                                 })) { pending in
                MultiSongPicker(title: "Songs for Video", initial: []) { keys in
                    if keys.isEmpty {
                        WallpaperFiles.deleteIfUnused(pending.file, settings: settings.settings)
                    } else {
                        settings.update {
                            $0.wallpapers.append(WallpaperEntry(videoFile: pending.file, songKeys: keys))
                        }
                    }
                    pendingVideo = nil
                }
            }
            .sheet(item: $editingEntry) { entry in
                MultiSongPicker(title: "Songs for Video", initial: entry.songKeys) { keys in
                    settings.update { settings in
                        if let i = settings.wallpapers.firstIndex(where: { $0.id == entry.id }) {
                            settings.wallpapers[i].songKeys = keys
                        }
                    }
                }
            }
            .alert("Couldn't Add Video", isPresented: $failed) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private struct PendingVideo: Identifiable, Equatable {
        let file: String
        var id: String { file }
    }

    private func chooseMenu(_ title: String, for target: Target) -> some View {
        Menu(title) {
            Button("From Photos", systemImage: "photo.on.rectangle") {
                self.target = target
                showPhotos = true
            }
            Button("From Files", systemImage: "folder") {
                self.target = target
                showFiles = true
            }
        }
    }

    private func addVideo(from url: URL, deleteSource: Bool) {
        defer { if deleteSource { try? FileManager.default.removeItem(at: url) } }
        guard let file = try? WallpaperFiles.importVideo(from: url) else {
            failed = true
            return
        }
        switch target {
        case .global:
            let old = settings.settings.globalWallpaperFile
            settings.update { $0.globalWallpaperFile = file }
            WallpaperFiles.deleteIfUnused(old, settings: settings.settings)
        case .newEntry:
            pendingVideo = file
        case nil:
            WallpaperFiles.deleteIfUnused(file, settings: settings.settings)
        }
        target = nil
    }
}

/// Pick any number of songs (search + select all shown).
struct MultiSongPicker: View {
    let title: String
    let initial: [String]
    let onDone: ([String]) -> Void

    @EnvironmentObject private var library: LibraryManager
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<String> = []
    @State private var query = ""

    var body: some View {
        let shown = sortSongs(searchSongs(library.songs, query), by: .title)

        NavigationStack {
            List {
                Button("Select All Shown") { selected.formUnion(shown.map(\.key)) }
                ForEach(shown) { song in
                    Button {
                        if selected.contains(song.key) { selected.remove(song.key) } else { selected.insert(song.key) }
                    } label: {
                        HStack {
                            SongRow(song: song, isCurrent: false)
                            Image(systemName: selected.contains(song.key) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(Color.accentColor)
                                .font(.title3)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: "Songs, artists, albums")
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if initial.isEmpty { onDone([]) }
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done (\(selected.count))") {
                        // Keep previously chosen songs that are missing from the library right now.
                        let missing = initial.filter { key in !library.songs.contains { $0.key == key } }
                        onDone(missing + library.songs.map(\.key).filter(selected.contains))
                        dismiss()
                    }
                }
            }
            .onAppear { selected = Set(initial) }
        }
    }
}

// MARK: - Playing the video

/// A muted, looping video filling the space behind Now Playing. Pauses in the background.
struct VideoWallpaperView: UIViewRepresentable {
    let url: URL
    let isActive: Bool

    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        let player = AVQueuePlayer()
        var looper: AVPlayerLooper?
        var url: URL?

        func load(_ url: URL) {
            guard url != self.url else { return }
            self.url = url
            let item = AVPlayerItem(url: url)
            player.removeAllItems()
            looper = AVPlayerLooper(player: player, templateItem: item)
        }
    }

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.player.isMuted = true
        view.player.preventsDisplaySleepDuringVideoPlayback = false
        view.playerLayer.player = view.player
        view.playerLayer.videoGravity = .resizeAspectFill
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        view.load(url)
        if isActive { view.player.play() } else { view.player.pause() }
    }

    static func dismantleUIView(_ view: PlayerView, coordinator: ()) {
        view.player.pause()
        view.looper = nil
    }
}
