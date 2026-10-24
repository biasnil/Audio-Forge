package com.biasnil.audioforge.data

import java.util.Locale

// Library grouping, sorting, searching and formatting. Plain Kotlin (no
// Android types) so it can be unit tested on the JVM.

/** An album or artist. An empty [name] is the "Unknown Album" / "Unknown Artist" group. */
data class TrackGroup(val name: String, val tracks: List<Track>)

/**
 * The library's albums, artists and genres, and every song A-Z: worked out
 * once per library change, off the main thread, so tabs open without
 * sorting thousands of songs. [ready] is false until the first result.
 */
data class LibraryGroups(
    val albums: List<TrackGroup> = emptyList(),
    val artists: List<TrackGroup> = emptyList(),
    val genres: List<TrackGroup> = emptyList(),
    val byTitle: List<Track> = emptyList(),
    val ready: Boolean = false,
)

fun libraryGroups(tracks: List<Track>) = LibraryGroups(
    albums = albumGroups(tracks),
    artists = artistGroups(tracks),
    genres = genreGroups(tracks),
    byTitle = sortTracks(tracks, TrackSort.Title),
    ready = true,
)

data class LibraryStats(val tracks: Int, val albums: Int, val artists: Int, val genres: Int)

enum class TrackSort {
    Title, Artist, Album, Year, RecentlyAdded, Bpm, Key;

    /** Sorts with an A-Z index down the side. */
    val isAlphabetical: Boolean get() = this == Title || this == Artist || this == Album
}

private val nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER

fun albumGroups(tracks: List<Track>): List<TrackGroup> = groupsBy(tracks) { it.album }

fun artistGroups(tracks: List<Track>): List<TrackGroup> = groupsBy(tracks) { it.artist }

/** Like the iOS Genres tab: "" (no genre tag) is the Unknown Genre group, last. */
fun genreGroups(tracks: List<Track>): List<TrackGroup> = groupsBy(tracks) { it.genre }

/** A genre's tracks, album by album like an artist's. */
fun tracksInGenre(tracks: List<Track>, genre: String): List<Track> =
    tracks.filter { it.genre.trim() == genre }
        .sortedWith(
            compareBy<Track, String>(nameOrder) { it.artist }
                .thenBy(nameOrder) { it.album }
                .thenBy { it.trackNumber }
                .thenBy(nameOrder) { it.title }
        )

/** An album's artist line: the one artist, or "" when several share it (shown as Various Artists). */
fun albumArtist(group: TrackGroup): String? {
    val artists = group.tracks.map { it.artist.trim() }.filter { it.isNotEmpty() }.toSet()
    return when (artists.size) {
        0 -> null
        1 -> artists.first()
        else -> ""
    }
}

/** How many different albums an artist's tracks come from. */
fun albumCount(tracks: List<Track>): Int = tracks.map { it.album.trim() }.toSet().size

/** The A-Z index letter for a track under an alphabetical sort ("#" for digits, symbols, other scripts). */
fun indexLetter(track: Track, sort: TrackSort): String {
    val text = when (sort) {
        TrackSort.Artist -> track.artist
        TrackSort.Album -> track.album
        else -> track.title
    }
    val first = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFD)
        .firstOrNull() ?: return "#"
    val letter = first.uppercaseChar()
    return if (letter in 'A'..'Z') letter.toString() else "#"
}

private fun groupsBy(tracks: List<Track>, key: (Track) -> String): List<TrackGroup> =
    tracks.groupBy { key(it).trim() }
        .map { (name, groupTracks) -> TrackGroup(name, groupTracks) }
        // A-Z ignoring case, with the unknown ("") group last
        .sortedWith(compareBy<TrackGroup> { it.name.isEmpty() }.thenBy(nameOrder) { it.name })

/** An album's tracks in disc/track order (untagged ones last, by title). */
fun tracksInAlbum(tracks: List<Track>, album: String): List<Track> =
    tracks.filter { it.album.trim() == album }
        .sortedWith(
            compareBy<Track> { it.trackNumber == 0 }
                .thenBy { it.trackNumber }
                .thenBy(nameOrder) { it.title }
        )

/** An artist's tracks, album by album, each in disc/track order. */
fun tracksByArtist(tracks: List<Track>, artist: String): List<Track> =
    tracks.filter { it.artist.trim() == artist }
        .sortedWith(
            compareBy<Track, String>(nameOrder) { it.album }
                .thenBy { it.trackNumber == 0 }
                .thenBy { it.trackNumber }
                .thenBy(nameOrder) { it.title }
        )

/** A folder in the phone's music library, with how many songs it holds (for the Folders tab's hide list). */
data class LibraryFolder(
    val key: String,
    val trackCount: Int,
    /** How many of them Android files as podcasts. */
    val systemPodcastCount: Int = 0,
) {
    /** "Music/Rock" (plus the storage volume if it isn't the internal one). */
    val path: String get() = folderPath(key)
    /** "Rock" */
    val name: String get() = path.substringAfterLast('/').ifEmpty { path }
}

/** "external_primary|Music/Rock/" -> "Music/Rock"; other volumes (SD cards) keep their id in front. */
fun folderPath(folderKey: String): String {
    val volume = folderKey.substringBefore('|', "")
    val path = folderKey.substringAfter('|').trimEnd('/')
    return if (volume.isEmpty() || volume == "external_primary") path else "$volume/$path"
}

/** Whether [folderKey] is [hidden] itself or inside it (keys end with '/', so "Music/" doesn't match "Music2/"). */
fun isInFolder(folderKey: String, hidden: String): Boolean =
    folderKey.isNotEmpty() && folderKey.startsWith(hidden)

/** Drops tracks whose folder is hidden, or inside a hidden folder. */
fun excludeFolders(tracks: List<Track>, hiddenFolders: Set<String>): List<Track> =
    if (hiddenFolders.isEmpty()) tracks
    else tracks.filter { track -> hiddenFolders.none { isInFolder(track.folderKey, it) } }

/** Every phone-library folder that directly holds songs, A-Z by path. */
fun folderCounts(tracks: List<Track>): List<LibraryFolder> =
    tracks.filter { it.folderKey.isNotEmpty() }
        .groupBy { it.folderKey }
        .map { (key, inFolder) -> LibraryFolder(key, inFolder.size, inFolder.count { it.systemPodcast }) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })

fun libraryStats(tracks: List<Track>) = LibraryStats(
    tracks = tracks.size,
    albums = tracks.map { it.album.trim() }.toSet().size,
    artists = tracks.map { it.artist.trim() }.toSet().size,
    genres = tracks.map { it.genre.trim() }.filter { it.isNotEmpty() }.toSet().size,
)

/** BPM and Key sorts use [analysisFor] (songs without an analysis go last). */
fun sortTracks(tracks: List<Track>, sort: TrackSort, analysisFor: (Track) -> SongAnalysis? = { null }): List<Track> {
    val byTitle = compareBy<Track, String>(nameOrder) { it.title }
    val comparator = when (sort) {
        TrackSort.Title -> byTitle
        TrackSort.Artist -> compareBy<Track, String>(nameOrder) { it.artist }
            .thenBy(nameOrder) { it.album }
            .thenBy { it.trackNumber }
            .then(byTitle)
        TrackSort.Album -> compareBy<Track, String>(nameOrder) { it.album }
            .thenBy { it.trackNumber }
            .then(byTitle)
        TrackSort.Year -> compareByDescending<Track> { it.year }.then(byTitle)
        TrackSort.RecentlyAdded -> compareByDescending<Track> { it.dateAddedMs }.then(byTitle)
        TrackSort.Bpm -> compareBy<Track> { analysisFor(it)?.bpm ?: Double.MAX_VALUE }.then(byTitle)
        // Camelot order: 1A, 1B, 2A, 2B...
        TrackSort.Key -> compareBy<Track> { track ->
            analysisFor(track)?.key?.let { it.camelotNumber * 2 + if (it.isMinor) 0 else 1 } ?: Int.MAX_VALUE
        }.then(byTitle)
    }
    return tracks.sortedWith(comparator)
}

/** Same fields the desktop search checks: title, artist, album. */
fun searchTracks(tracks: List<Track>, query: String): List<Track> {
    val needle = query.trim()
    if (needle.isEmpty()) return tracks
    return tracks.filter { track ->
        track.title.contains(needle, ignoreCase = true) ||
            track.artist.contains(needle, ignoreCase = true) ||
            track.album.contains(needle, ignoreCase = true)
    }
}

/**
 * Phone-library tracks first, then added-folder tracks, dropping duplicates:
 * the same file can be reachable both ways (and via overlapping folders)
 * under different URIs, so name + size identifies it. Files of unknown size
 * are never treated as duplicates.
 */
fun mergeSources(phoneTracks: List<Track>, folderTracks: List<Track>): List<Track> {
    val seen = HashSet<String>()
    return (phoneTracks + folderTracks).filter { track ->
        val key = if (track.sizeBytes > 0) {
            track.fileName.lowercase(Locale.ROOT) + "\u0000" + track.sizeBytes
        } else {
            track.uri
        }
        seen.add(key)
    }
}

/** e.g. "FLAC  •  900 kb/s  •  44.1 kHz"; empty if the bitrate isn't known. */
fun formatAudioInfo(track: Track): String {
    if (track.bitrateKbps <= 0) return ""
    val format = track.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT).ifEmpty { "Audio" }
    val parts = mutableListOf(format, "${track.bitrateKbps} kb/s")
    if (track.sampleRateHz > 0) {
        parts += String.format(Locale.ROOT, "%.1f kHz", track.sampleRateHz / 1000.0)
    }
    return parts.joinToString("  •  ")
}

/** m:ss, or h:mm:ss for long tracks (like the iOS app). */
fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
