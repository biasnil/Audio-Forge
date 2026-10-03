import SwiftUI

// MARK: - Grouping

struct AlbumGroup: Identifiable {
    let name: String
    let artist: String
    let artwork: Data?
    var id: String { name }

    static func make(from songs: [Song]) -> [AlbumGroup] {
        Dictionary(grouping: songs, by: \.album)
            .map { name, items in
                let artists = Set(items.map(\.artist))
                return AlbumGroup(
                    name: name,
                    artist: artists.count == 1 ? items[0].artist : "Various Artists",
                    artwork: items.first { $0.artworkData != nil }?.artworkData
                )
            }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

struct ArtistGroup: Identifiable {
    let name: String
    let songCount: Int
    let albumCount: Int
    let artwork: Data?
    var id: String { name }

    static func make(from songs: [Song]) -> [ArtistGroup] {
        Dictionary(grouping: songs, by: \.artist)
            .map { name, items in
                ArtistGroup(
                    name: name,
                    songCount: items.count,
                    albumCount: Set(items.map(\.album)).count,
                    artwork: items.first { $0.artworkData != nil }?.artworkData
                )
            }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

// MARK: - Albums tab

struct AlbumsView: View {
    @EnvironmentObject private var library: LibraryManager

    private let columns = [GridItem(.flexible(), spacing: 16),
                           GridItem(.flexible(), spacing: 16)]

    var body: some View {
        let albums = AlbumGroup.make(from: library.songs)

        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 20) {
                    ForEach(albums) { album in
                        NavigationLink(value: album.name) {
                            VStack(alignment: .leading, spacing: 4) {
                                SquareArtwork(data: album.artwork)
                                Text(album.name)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(1)
                                Text(album.artist)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding()
            }
            .overlay {
                if albums.isEmpty {
                    ContentUnavailableView("No Albums", systemImage: "square.stack",
                                           description: Text("Import songs to see their albums."))
                }
            }
            .navigationTitle("Albums")
            .navigationDestination(for: String.self) { name in
                SongCollectionView(title: name, sortByAlbum: false) { $0.album == name }
            }
        }
    }
}

// MARK: - Artists tab

struct ArtistsView: View {
    @EnvironmentObject private var library: LibraryManager

    var body: some View {
        let artists = ArtistGroup.make(from: library.songs)

        NavigationStack {
            List(artists) { artist in
                NavigationLink(value: artist.name) {
                    HStack(spacing: 12) {
                        SquareArtwork(data: artist.artwork, cornerRadius: 22)
                            .frame(width: 44, height: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(artist.name).lineLimit(1)
                            Text("\(artist.songCount) song\(artist.songCount == 1 ? "" : "s") · "
                                 + "\(artist.albumCount) album\(artist.albumCount == 1 ? "" : "s")")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if artists.isEmpty {
                    ContentUnavailableView("No Artists", systemImage: "music.mic",
                                           description: Text("Import songs to see their artists."))
                }
            }
            .navigationTitle("Artists")
            .navigationDestination(for: String.self) { name in
                SongCollectionView(title: name, sortByAlbum: true) { $0.artist == name }
            }
        }
    }
}

// MARK: - Songs of one album / artist

struct SongCollectionView: View {
    let title: String
    let sortByAlbum: Bool
    let match: (Song) -> Bool

    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager

    private var songs: [Song] {
        library.songs.filter(match).sorted { a, b in
            if sortByAlbum && a.album != b.album {
                return a.album.localizedStandardCompare(b.album) == .orderedAscending
            }
            return a.title.localizedStandardCompare(b.title) == .orderedAscending
        }
    }

    var body: some View {
        let songs = self.songs

        List {
            Section {
                VStack(spacing: 12) {
                    SquareArtwork(data: songs.first { $0.artworkData != nil }?.artworkData)
                        .frame(width: 220)
                        .shadow(color: .black.opacity(0.2), radius: 12, y: 6)
                    Text("\(songs.count) song\(songs.count == 1 ? "" : "s")")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
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
                    .disabled(songs.isEmpty)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
            }

            Section {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    Button {
                        player.play(songs, startAt: index)
                    } label: {
                        SongRow(song: song, isCurrent: player.currentSong?.id == song.id)
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Flexible square artwork

struct SquareArtwork: View {
    let data: Data?
    var cornerRadius: CGFloat = 8

    var body: some View {
        Color.gray.opacity(0.2)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let data, let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "music.note")
                        .font(.title)
                        .foregroundStyle(.secondary)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}
