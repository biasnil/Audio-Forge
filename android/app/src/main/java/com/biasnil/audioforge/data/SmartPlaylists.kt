package com.biasnil.audioforge.data

import java.util.UUID
import kotlin.math.abs

// Smart playlists, the duplicate finder and .m3u reading/writing -- ports of
// the iOS app's SmartPlaylists.swift, DuplicatesView.swift and
// PlaylistManager.importM3U. Plain Kotlin (no Android types).

/** What a smart-playlist rule looks at. */
enum class SmartField(val kind: Kind) {
    Title(Kind.Text), Artist(Kind.Text), Album(Kind.Text), Genre(Kind.Text),
    Year(Kind.Number), PlayCount(Kind.Number), LastPlayedDays(Kind.Number), AddedDays(Kind.Number),
    Loved(Kind.Bool), Bpm(Kind.Number), Key(Kind.Text), Minutes(Kind.Number);

    enum class Kind { Text, Number, Bool }

    val operators: List<SmartOperator>
        get() = when (kind) {
            Kind.Text -> listOf(SmartOperator.Contains, SmartOperator.Is, SmartOperator.IsNot)
            Kind.Number -> listOf(SmartOperator.Is, SmartOperator.MoreThan, SmartOperator.LessThan)
            Kind.Bool -> listOf(SmartOperator.Yes, SmartOperator.No)
        }
}

enum class SmartOperator { Contains, Is, IsNot, MoreThan, LessThan, Yes, No }

data class SmartRule(
    val id: String = UUID.randomUUID().toString(),
    val field: SmartField = SmartField.Genre,
    val op: SmartOperator = SmartOperator.Contains,
    val value: String = "",
)

enum class SmartSort { Title, MostPlayed, RecentlyPlayed, RecentlyAdded, Random, Bpm }

/** A playlist defined by rules ("genre contains Rock and year is more than 2010"); it updates itself. */
data class SmartPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rules: List<SmartRule> = listOf(SmartRule()),
    val matchAll: Boolean = true,
    /** null = no limit. */
    val limit: Int? = null,
    val sort: SmartSort = SmartSort.Title,
    /** Built-in lists (Loved, Most Played...) can't be edited or deleted. */
    val builtIn: Boolean = false,
) {
    /** The songs that match, sorted and limited. */
    fun tracks(
        library: List<Track>,
        stats: Map<String, SongStats>,
        analysisFor: (Track) -> SongAnalysis? = { null },
        nowMs: Long = System.currentTimeMillis(),
    ): List<Track> {
        val matching = library.filter { track ->
            if (rules.isEmpty()) return@filter true
            val statsFor = stats[track.uri] ?: SongStats()
            val analysis = analysisFor(track)
            if (matchAll) rules.all { matches(it, track, statsFor, analysis, nowMs) }
            else rules.any { matches(it, track, statsFor, analysis, nowMs) }
        }
        val sorted = when (sort) {
            SmartSort.Title -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SmartSort.MostPlayed -> matching.sortedByDescending { stats[it.uri]?.playCount ?: 0 }
            SmartSort.RecentlyPlayed -> matching.sortedByDescending { stats[it.uri]?.lastPlayedMs ?: 0L }
            SmartSort.RecentlyAdded -> matching.sortedByDescending { it.dateAddedMs }
            SmartSort.Random -> matching.shuffled()
            SmartSort.Bpm -> matching.sortedBy { analysisFor(it)?.bpm ?: Double.MAX_VALUE }
        }
        val max = limit
        return if (max != null && max > 0 && sorted.size > max) sorted.take(max) else sorted
    }

    private fun matches(rule: SmartRule, track: Track, stats: SongStats, analysis: SongAnalysis?, nowMs: Long): Boolean {
        val value = rule.value.trim()
        return when (rule.field.kind) {
            SmartField.Kind.Bool -> (rule.op == SmartOperator.Yes) == stats.favorite
            SmartField.Kind.Text -> {
                if (rule.field == SmartField.Key) {
                    // Compare keys, not spellings: "8A" matches "Am".
                    val key = analysis?.key ?: return rule.op == SmartOperator.IsNot
                    MusicKey.parse(value)?.let { wanted -> return (key == wanted) != (rule.op == SmartOperator.IsNot) }
                }
                val text = when (rule.field) {
                    SmartField.Title -> track.title
                    SmartField.Artist -> track.artist
                    SmartField.Album -> track.album
                    SmartField.Genre -> track.genre
                    SmartField.Key -> analysis?.key?.let { "${it.camelot} ${it.shortName} ${it.name}" }.orEmpty()
                    else -> ""
                }
                when (rule.op) {
                    SmartOperator.Contains -> value.isEmpty() || text.contains(value, ignoreCase = true)
                    SmartOperator.Is -> text.equals(value, ignoreCase = true)
                    SmartOperator.IsNot -> !text.equals(value, ignoreCase = true)
                    else -> false
                }
            }
            SmartField.Kind.Number -> {
                val target = value.toDoubleOrNull() ?: return true // an unfinished rule doesn't filter
                val number: Double = when (rule.field) {
                    SmartField.Year -> if (track.year > 0) track.year.toDouble() else return false
                    SmartField.PlayCount -> stats.playCount.toDouble()
                    SmartField.LastPlayedDays ->
                        if (stats.lastPlayedMs > 0) (nowMs - stats.lastPlayedMs) / DAY_MS else return false
                    SmartField.AddedDays ->
                        if (track.dateAddedMs > 0) (nowMs - track.dateAddedMs) / DAY_MS else return false
                    SmartField.Minutes -> track.durationMs / 60_000.0
                    SmartField.Bpm -> analysis?.bpm ?: return false
                    else -> return false
                }
                when (rule.op) {
                    SmartOperator.Is -> abs(number - target) < 0.5
                    SmartOperator.MoreThan -> number > target
                    SmartOperator.LessThan -> number < target
                    else -> false
                }
            }
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000.0

        const val LOVED_ID = "builtin:loved"
        const val MOST_PLAYED_ID = "builtin:most-played"
        const val RECENTLY_PLAYED_ID = "builtin:recently-played"
        const val RECENTLY_ADDED_ID = "builtin:recently-added"
        const val NEVER_PLAYED_ID = "builtin:never-played"

        /** The built-in smart lists shown at the top of Playlists (names are shown translated, see the UI). */
        val BUILT_IN: List<SmartPlaylist> = listOf(
            SmartPlaylist(LOVED_ID, "Loved", listOf(SmartRule(field = SmartField.Loved, op = SmartOperator.Yes)), builtIn = true),
            SmartPlaylist(
                MOST_PLAYED_ID, "Most Played",
                listOf(SmartRule(field = SmartField.PlayCount, op = SmartOperator.MoreThan, value = "0")),
                limit = 100, sort = SmartSort.MostPlayed, builtIn = true,
            ),
            SmartPlaylist(
                RECENTLY_PLAYED_ID, "Recently Played",
                listOf(SmartRule(field = SmartField.PlayCount, op = SmartOperator.MoreThan, value = "0")),
                limit = 100, sort = SmartSort.RecentlyPlayed, builtIn = true,
            ),
            SmartPlaylist(RECENTLY_ADDED_ID, "Recently Added", emptyList(), limit = 100, sort = SmartSort.RecentlyAdded, builtIn = true),
            SmartPlaylist(
                NEVER_PLAYED_ID, "Never Played",
                listOf(SmartRule(field = SmartField.PlayCount, op = SmartOperator.Is, value = "0")),
                sort = SmartSort.RecentlyAdded, builtIn = true,
            ),
        )
    }
}

// --- Duplicates -------------------------------------------------------------

/**
 * The same song stored more than once: same title and artist (ignoring
 * case, punctuation and "(Remastered)"-style suffixes) and a length within
 * 2 seconds. Groups of 2+, A-Z by title.
 */
fun duplicateGroups(tracks: List<Track>): List<List<Track>> {
    val bracketed = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
    fun normalized(text: String): String =
        bracketed.replace(text.lowercase(), "").filter { it.isLetterOrDigit() }

    val result = ArrayList<List<Track>>()
    for (candidates in tracks.groupBy { normalized(it.title) + "|" + normalized(it.artist) }.values) {
        if (candidates.size < 2) continue
        // Split by length, so a live version or a remix isn't called a duplicate.
        var cluster = ArrayList<Track>()
        for (track in candidates.sortedBy { it.durationMs }) {
            val last = cluster.lastOrNull()
            if (last != null && track.durationMs - last.durationMs > 2_000) {
                if (cluster.size > 1) result += cluster
                cluster = ArrayList()
            }
            cluster += track
        }
        if (cluster.size > 1) result += cluster
    }
    return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it[0].title })
}

// --- .m3u ---------------------------------------------------------------------

/** One entry read from an .m3u file: the path line and the #EXTINF "Artist - Title" label, if any. */
data class M3uEntry(val path: String, val label: String?)

fun parseM3u(text: String): List<M3uEntry> {
    val entries = ArrayList<M3uEntry>()
    var pendingLabel: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim().removePrefix("\uFEFF") // byte-order mark
        if (line.isEmpty()) continue
        if (line.uppercase().startsWith("#EXTINF:")) {
            pendingLabel = line.substringAfter(',', "").trim().ifEmpty { null }
            continue
        }
        if (line.startsWith("#")) continue
        entries += M3uEntry(line, pendingLabel)
        pendingLabel = null
    }
    return entries
}

/**
 * Matches .m3u entries to library tracks by the longest path ending that's
 * in the library, then file name, then "Artist - Title" (like the iOS app).
 * [pathOf] gives a track's path inside its storage ("Music/Rock/a.mp3").
 * Returns the matched tracks (each once, in file order).
 */
fun matchM3u(entries: List<M3uEntry>, library: List<Track>, pathOf: (Track) -> String): List<Track> {
    fun normalize(path: String) = path.replace('\\', '/').lowercase()
    val byPath = HashMap<String, Track>()
    val byName = HashMap<String, Track>()
    val byLabel = HashMap<String, Track>()
    for (track in library) {
        // Every ending of the path, so "rock/a.mp3" matches "Music/Rock/a.mp3".
        val parts = normalize(pathOf(track)).split('/').filter { it.isNotEmpty() }
        for (start in parts.indices) byPath.putIfAbsent(parts.subList(start, parts.size).joinToString("/"), track)
        byName.putIfAbsent(track.fileName.lowercase(), track)
        byLabel.putIfAbsent("${track.artist} - ${track.title}".lowercase(), track)
    }
    val seen = HashSet<String>()
    val result = ArrayList<Track>()
    for (entry in entries) {
        val decoded = try {
            java.net.URLDecoder.decode(entry.path.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            entry.path
        }
        val path = normalize(decoded).removePrefix("file://")
        val parts = path.split('/').filter { it.isNotEmpty() }
        var match: Track? = null
        for (start in parts.indices) {
            match = byPath[parts.subList(start, parts.size).joinToString("/")]
            if (match != null) break
        }
        match = match ?: byName[parts.lastOrNull().orEmpty()] ?: entry.label?.let { byLabel[it.lowercase()] }
        if (match != null && seen.add(match.uri)) result += match
    }
    return result
}

/** An .m3u8 playlist: #EXTINF lines plus each song's path inside its storage (matched by file name elsewhere). */
fun writeM3u(tracks: List<Track>, pathOf: (Track) -> String): String = buildString {
    append("#EXTM3U\n")
    for (track in tracks) {
        append("#EXTINF:").append(track.durationMs / 1000).append(',')
        append(if (track.artist.isEmpty()) track.title else "${track.artist} - ${track.title}").append('\n')
        append(pathOf(track)).append('\n')
    }
}
