import SwiftUI
import Combine

// MARK: - Grouping (computed once per library change, in LibraryManager)

struct AlbumGroup: Identifiable {
    let name: String
    let artist: String
    let artwork: Data?
    /// Key of the song the artwork came from (for the thumbnail cache).
    let artworkKey: String?
    var id: String { name }

    static func make(from songs: [Song]) -> [AlbumGroup] {
        Dictionary(grouping: songs, by: \.album)
            .map { name, items in
                let albumArtist = items.first { !$0.albumArtist.isEmpty }?.albumArtist
                let artists = Set(items.map(\.artist))
                let withArt = items.first { $0.artworkData != nil }
                return AlbumGroup(
                    name: name,
                    artist: albumArtist ?? (artists.count == 1 ? items[0].artist : "Various Artists"),
                    artwork: withArt?.artworkData,
                    artworkKey: withArt?.key
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
    let artworkKey: String?
    var id: String { name }

    static func make(from songs: [Song]) -> [ArtistGroup] {
        Dictionary(grouping: songs, by: \.artist)
            .map { name, items in
                let withArt = items.first { $0.artworkData != nil }
                return ArtistGroup(
                    name: name,
                    songCount: items.count,
                    albumCount: Set(items.map(\.album)).count,
                    artwork: withArt?.artworkData,
                    artworkKey: withArt?.key
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
        let albums = library.albums

        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 20) {
                    ForEach(albums) { album in
                        NavigationLink(value: album.name) {
                            VStack(alignment: .leading, spacing: 4) {
                                SquareArtwork(data: album.artwork, cacheKey: album.artworkKey)
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
        let artists = library.artists

        NavigationStack {
            List(artists) { artist in
                NavigationLink(value: artist.name) {
                    HStack(spacing: 12) {
                        ArtworkView(data: artist.artwork, size: 44, cacheKey: artist.artworkKey, cornerRadius: 22)
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

// MARK: - Songs of one album / artist / folder

struct SongCollectionView: View {
    let title: String
    let sortByAlbum: Bool
    let match: (Song) -> Bool

    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var player: PlayerManager
    /// Filtered and sorted only when the library changes, not on every redraw.
    @State private var songs: [Song] = []

    /// Album pages in disc/track order; artist pages by album, then track.
    private func compute(_ all: [Song]) -> [Song] {
        sortSongs(all.filter(match), by: sortByAlbum ? .artist : .album)
    }

    var body: some View {
        let header = songs.first { $0.artworkData != nil }

        List {
            Section {
                VStack(spacing: 12) {
                    SquareArtwork(data: header?.artworkData, cacheKey: header?.key, expectedSize: 220)
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
                    .contextMenu { SongMenu(song: song) }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { songs = compute(library.songs) }
        .onReceive(library.$songs.dropFirst()) { songs = compute($0) }
    }
}
