import SwiftUI
import Combine

/// Finds the same song stored more than once: same title and artist (ignoring case, punctuation
/// and "(Remastered)"-style suffixes) and a length within 2 seconds.
nonisolated enum DuplicateFinder {
    static func groups(_ songs: [Song]) -> [[Song]] {
        func normalized(_ text: String) -> String {
            text.lowercased()
                .replacingOccurrences(of: "\\s*[(\\[（【][^)\\]）】]*[)\\]）】]", with: "", options: .regularExpression)
                .unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }
                .map(String.init).joined()
        }
        let byName = Dictionary(grouping: songs) { "\(normalized($0.title))|\(normalized($0.artist))" }
        var result: [[Song]] = []
        for (_, candidates) in byName where candidates.count > 1 {
            // Split by length, so a live version or a remix isn't called a duplicate.
            var cluster: [Song] = []
            for song in candidates.sorted(by: { $0.duration < $1.duration }) {
                if let last = cluster.last, song.duration - last.duration > 2 {
                    if cluster.count > 1 { result.append(cluster) }
                    cluster = []
                }
                cluster.append(song)
            }
            if cluster.count > 1 { result.append(cluster) }
        }
        return result.sorted { $0[0].title.localizedStandardCompare($1[0].title) == .orderedAscending }
    }
}

struct DuplicatesView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var editor: SongEditor
    @EnvironmentObject private var player: PlayerManager
    @State private var groups: [[Song]] = []
    @State private var searched = false

    var body: some View {
        List {
            ForEach(groups, id: \.first?.id) { group in
                Section {
                    ForEach(group) { song in
                        Button {
                            player.play(group, startAt: group.firstIndex(of: song) ?? 0, from: "Duplicates")
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                                Text(location(of: song))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                        .swipeActions {
                            Button("Delete", systemImage: "trash", role: .destructive) {
                                editor.pendingDelete = [song]     // asks first
                            }
                        }
                    }
                } header: {
                    Text("\(group[0].title) — \(group.count) copies")
                }
            }
        }
        .overlay {
            if searched && groups.isEmpty {
                ContentUnavailableView("No Duplicates", systemImage: "checkmark.circle",
                                       description: Text("Every song is stored once."))
            } else if !searched {
                ProgressView()
            }
        }
        .navigationTitle("Duplicates")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: library.allSongs.count) { await search(library.allSongs) }
        .onReceive(library.$allSongs.dropFirst()) { songs in Task { await search(songs) } }
    }

    private func search(_ songs: [Song]) async {
        groups = await Task.detached(priority: .userInitiated) { DuplicateFinder.groups(songs) }.value
        searched = true
    }

    /// "Rock/Album · 320 kbps · FLAC" — enough to decide which copy to keep.
    private func location(of song: Song) -> String {
        let path = song.key.split(separator: "/", maxSplits: 1).last.map(String.init) ?? song.fileName
        var parts = [path]
        if song.bitrateKbps > 0 { parts.append("\(song.bitrateKbps) kbps") }
        parts.append(song.url.pathExtension.uppercased())
        return parts.joined(separator: " · ")
    }
}
