package com.biasnil.audioforge.data

import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

// Missing tags and covers from two free services that need no account or key:
// Apple's iTunes Search API first, Deezer's public API if Apple has nothing.
// What's sent: the song's title and artist, or the album's name and artist.
// Plain Kotlin, tested on the JVM; MetadataRepository does the requests.

/** What a service knows about a song or album; empty / 0 = it didn't say. */
data class FoundInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val trackNumber: Int = 0,
    /** A large version of the cover (600 px or more), if there is one. */
    val coverUrl: String? = null,
    /** "Apple" or "Deezer", shown next to what was found. */
    val source: String = "",
)

fun itunesSongSearchUrl(title: String, artist: String): String =
    "https://itunes.apple.com/search?media=music&entity=song&limit=15&term=" + encode("$artist $title")

fun itunesAlbumSearchUrl(album: String, artist: String): String =
    "https://itunes.apple.com/search?media=music&entity=album&limit=15&term=" + encode("$artist $album")

fun deezerSongSearchUrl(title: String, artist: String): String =
    "https://api.deezer.com/search?limit=15&q=" + encode(if (artist.isBlank()) title else "artist:\"$artist\" track:\"$title\"")

fun deezerAlbumSearchUrl(album: String, artist: String): String =
    "https://api.deezer.com/search/album?limit=15&q=" + encode(if (artist.isBlank()) album else "artist:\"$artist\" album:\"$album\"")

private fun encode(text: String) = URLEncoder.encode(text.trim(), "UTF-8")

/** iTunes song (or album) results. Covers come at 100 px; the same URL serves 600 px. */
fun parseItunesResults(json: String): List<FoundInfo> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { i ->
        val obj = results.optJSONObject(i) ?: return@mapNotNull null
        FoundInfo(
            title = obj.optString("trackName"),
            artist = obj.optString("artistName"),
            album = obj.optString("collectionName"),
            albumArtist = obj.optString("collectionArtistName").ifEmpty {
                if (obj.optString("wrapperType") == "collection") obj.optString("artistName") else ""
            },
            year = obj.optString("releaseDate").take(4).toIntOrNull() ?: 0,
            genre = obj.optString("primaryGenreName"),
            trackNumber = obj.optInt("trackNumber", 0),
            coverUrl = obj.optString("artworkUrl100").ifEmpty { null }?.replace("100x100bb", "600x600bb"),
            source = "Apple",
        )
    }
}

/** Deezer track results (no year, genre or track number in a search) or album results. */
fun parseDeezerResults(json: String): List<FoundInfo> {
    val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).mapNotNull { i ->
        val obj = data.optJSONObject(i) ?: return@mapNotNull null
        val artist = obj.optJSONObject("artist")?.optString("name").orEmpty()
        val albumObj = obj.optJSONObject("album")
        if (albumObj != null) {
            FoundInfo(
                title = obj.optString("title"),
                artist = artist,
                album = albumObj.optString("title"),
                coverUrl = albumObj.optString("cover_xl").ifEmpty { albumObj.optString("cover_big") }.ifEmpty { null },
                source = "Deezer",
            )
        } else {
            FoundInfo(
                album = obj.optString("title"),
                artist = artist,
                albumArtist = artist,
                coverUrl = obj.optString("cover_xl").ifEmpty { obj.optString("cover_big") }.ifEmpty { null },
                source = "Deezer",
            )
        }
    }
}

/**
 * For comparing names: lower case, no accents, no "(feat. ...)" /
 * "[Remastered]" / " - Remastered 2011" extras, punctuation as spaces.
 */
fun normalizeName(text: String): String {
    var s = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    s = s.lowercase(Locale.ROOT)
    s = s.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
    s = s.replace(Regex("\\s[-–]\\s.*(remaster|version|edit|mix|live|mono|stereo).*$"), " ")
    s = s.replace("&", " and ")
    s = s.replace(Regex("['’`]"), "") // "Don't" = "Dont"
    s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    return s
}

private fun nameScore(found: String, wanted: String): Int {
    val a = normalizeName(found)
    val b = normalizeName(wanted)
    if (a.isEmpty() || b.isEmpty()) return 0
    return when {
        a == b -> 3
        a.startsWith(b) || b.startsWith(a) -> 2
        a.contains(b) || b.contains(a) -> 1
        else -> 0
    }
}

/**
 * The result that is really this song: the title must match, and the
 * artist too when we know it. The closest match wins; ties keep the
 * service's own order. Null = none is a safe match.
 */
fun bestSongMatch(results: List<FoundInfo>, title: String, artist: String): FoundInfo? =
    results.mapNotNull { found ->
        val titleScore = nameScore(found.title, title)
        val artistScore = if (artist.isBlank()) 1 else nameScore(found.artist, artist)
        if (titleScore == 0 || artistScore == 0) null else found to titleScore * 2 + artistScore
    }.maxByOrNull { it.second }?.first

/** Like [bestSongMatch], for an album by name (and artist when known), with a cover. */
fun bestAlbumMatch(results: List<FoundInfo>, album: String, artist: String): FoundInfo? =
    results.filter { it.coverUrl != null }.mapNotNull { found ->
        val albumScore = nameScore(found.album, album)
        val artistScore = if (artist.isBlank()) 1 else maxOf(nameScore(found.artist, artist), nameScore(found.albumArtist, artist))
        if (albumScore == 0 || artistScore == 0) null else found to albumScore * 2 + artistScore
    }.maxByOrNull { it.second }?.first

/**
 * Tag-editor fields (jaudiotagger key -> value) with the empty ones among
 * ALBUM, ALBUM_ARTIST, YEAR, GENRE and TRACK filled from [found]; fields
 * that already have a value are never changed. Returns which keys it filled.
 */
fun fillEmptyFields(fields: List<Pair<String, String>>, found: FoundInfo): Pair<List<Pair<String, String>>, Set<String>> {
    val offered = mapOf(
        "ALBUM" to found.album,
        "ALBUM_ARTIST" to found.albumArtist,
        "YEAR" to if (found.year > 0) found.year.toString() else "",
        "GENRE" to found.genre,
        "TRACK" to if (found.trackNumber > 0) found.trackNumber.toString() else "",
    ).filterValues { it.isNotBlank() }
    val filled = HashSet<String>()
    val updated = fields.map { (key, value) ->
        val new = offered[key]
        if (value.isBlank() && new != null) {
            filled += key
            key to new
        } else key to value
    }
    return updated to filled
}

/** "album|artist", normalized: the key a found cover is saved under (see MetadataRepository). */
fun albumCoverKey(album: String, artist: String): String = normalizeName(album) + "|" + normalizeName(artist)
