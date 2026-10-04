import SwiftUI
import Combine

// MARK: - Grouping (computed once per library change, in LibraryManager)

struct AlbumGroup: Identifiable, Hashable {
    /// See `key(for:)`.
    let id: String
    let name: String
    let artist: String
    /// Cover of the first song that has one.
    let artworkID: String?

    /// Which album a song belongs to. The name alone isn't enough ("Greatest Hits" by two
    /// artists would merge), so it's the name plus the album artist; without an album artist,
    /// the folder (a compilation's songs share one), plus the artist when the folder is a whole
    /// library root where unrelated songs sit together. Untagged songs group by folder.
    static func key(for song: Song) -> String {
        let isRootFolder = !song.folderKey.contains("/")
        if song.album == "Unknown Album" {
            return "\u{1}unknown|\(song.folderKey)" + (isRootFolder ? "|\(song.artist.lowercased())" : "")
        }
        let album = song.album.lowercased()
        if !song.albumArtist.isEmpty { return "\(album)|aa:\(song.albumArtist.lowercased())" }
        return "\(album)|f:\(song.folderKey)" + (isRootFolder ? "|\(song.artist.lowercased())" : "")
    }

    static func make(from songs: [Song]) -> [AlbumGroup] {
        Dictionary(grouping: songs, by: key(for:))
            .map { key, items in
                let albumArtist = items.first { !$0.albumArtist.isEmpty }?.albumArtist
                let artists = Set(items.map(\.artist))
                return AlbumGroup(
                    id: key,
                    name: items[0].album,
                    artist: albumArtist ?? (artists.count == 1 ? items[0].artist : "Various Artists"),
                    artworkID: items.lazy.compactMap(\.artworkID).first
                )
            }
            .sorted { a, b in
                let byName = a.name.localizedStandardCompare(b.name)
                if byName != .orderedSame { return byName == .orderedAscending }
                return a.artist.localizedStandardCompare(b.artist) == .orderedAscending
            }
    }
}

struct ArtistGroup: Identifiable {
    let name: String
    let songCount: Int
    let albumCount: Int
    let artworkID: String?
    var id: String { name }

    static func make(from songs: [Song]) -> [ArtistGroup] {
        Dictionary(grouping: songs, by: \.artist)
            .map { name, items in
                ArtistGroup(
                    name: name,
                    songCount: items.count,
                    albumCount: Set(items.map(\.album)).count,
                    artworkID: items.lazy.compactMap(\.artworkID).first
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

        TabStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 20) {
                    ForEach(albums) { album in
                        NavigationLink(value: album) {
                            VStack(alignment: .leading, spacing: 4) {
                                SquareArtwork(artworkID: album.artworkID)
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
            .navigationDestination(for: AlbumGroup.self) { album in
                SongCollectionView(title: album.name, sortByAlbum: false) { AlbumGroup.key(for: $0) == album.id }
            }
        }
    }
}

// MARK: - Artists tab

struct ArtistsView: View {
    @EnvironmentObject private var library: LibraryManager

    var body: some View {
        let artists = library.artists

        TabStack {
            List(artists) { artist in
                NavigationLink(value: artist.name) {
                    HStack(spacing: 12) {
                        ArtworkView(artworkID: artist.artworkID, size: 44, cornerRadius: 22)
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
        let headerArtwork = songs.lazy.compactMap(\.artworkID).first

        List {
            Section {
                VStack(spacing: 12) {
                    SquareArtwork(artworkID: headerArtwork, expectedSize: 220)
                        .frame(width: 220)
                        .shadow(color: .black.opacity(0.2), radius: 12, y: 6)
                    Text("\(songs.count) song\(songs.count == 1 ? "" : "s")")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        Button {
                            player.play(songs, startAt: 0, from: title)
                        } label: {
                            Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        Button {
                            player.playShuffled(songs, from: title)
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
                        player.play(songs, startAt: index, from: title)
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
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                        player.playNext(songs)
                    }
                    Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") {
                        player.addToQueue(songs)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(songs.isEmpty)
            }
        }
        .onAppear { songs = compute(library.songs) }
        .onReceive(library.$songs.dropFirst()) { songs = compute($0) }
    }
}

// MARK: - Genres tab

struct GenreGroup: Identifiable, Hashable {
    let name: String
    let songCount: Int
    let artworkID: String?
    var id: String { name }

    static let unknown = "Unknown Genre"

    static func make(from songs: [Song]) -> [GenreGroup] {
        Dictionary(grouping: songs) { $0.genre.isEmpty ? unknown : $0.genre }
            .map { name, items in
                GenreGroup(name: name, songCount: items.count,
                           artworkID: items.lazy.compactMap(\.artworkID).first)
            }
            .sorted { a, b in
                if (a.name == unknown) != (b.name == unknown) { return b.name == unknown }
                return a.name.localizedStandardCompare(b.name) == .orderedAscending
            }
    }
}

struct GenresView: View {
    @EnvironmentObject private var library: LibraryManager

    var body: some View {
        let genres = library.genres

        TabStack {
            List(genres) { genre in
                NavigationLink(value: genre) {
                    HStack(spacing: 12) {
                        ArtworkView(artworkID: genre.artworkID, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(genre.name).lineLimit(1)
                            Text("\(genre.songCount) song\(genre.songCount == 1 ? "" : "s")")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if genres.isEmpty {
                    ContentUnavailableView("No Genres", systemImage: "guitars",
                                           description: Text("Songs with a genre tag show up here."))
                }
            }
            .navigationTitle("Genres")
            .navigationDestination(for: GenreGroup.self) { genre in
                SongCollectionView(title: genre.name, sortByAlbum: true) { song in
                    genre.name == GenreGroup.unknown ? song.genre.isEmpty : song.genre == genre.name
                }
            }
        }
    }
}
