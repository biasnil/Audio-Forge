package com.biasnil.audioforge.data

import java.util.Locale

// Library grouping, sorting, searching and formatting. Plain Kotlin (no
// Android types) so it can be unit tested on the JVM.

/** An album or artist. An empty [name] is the "Unknown Album" / "Unknown Artist" group. */
data class TrackGroup(val name: String, val tracks: List<Track>)

data class LibraryStats(val tracks: Int, val albums: Int, val artists: Int, val genres: Int)

enum class TrackSort { Title, Artist, Album, Year }

private val nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER

fun albumGroups(tracks: List<Track>): List<TrackGroup> = groupsBy(tracks) { it.album }

fun artistGroups(tracks: List<Track>): List<TrackGroup> = groupsBy(tracks) { it.artist }

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

fun libraryStats(tracks: List<Track>) = LibraryStats(
    tracks = tracks.size,
    albums = tracks.map { it.album.trim() }.toSet().size,
    artists = tracks.map { it.artist.trim() }.toSet().size,
    genres = tracks.map { it.genre.trim() }.filter { it.isNotEmpty() }.toSet().size,
)

fun sortTracks(tracks: List<Track>, sort: TrackSort): List<Track> {
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

/** mm:ss, like the desktop time labels. */
fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
