package com.biasnil.audioforge.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The duplicate finder (Settings -> Find Duplicates): copies of the same song,
// even when their names differ a little ("01 - Yellow" / "Yellow",
// "Don't Stop Me Now - 2011 Remaster" / "Dont Stop Me Now"). Pure Kotlin, so
// it's tested on the JVM; the page runs it on Dispatchers.Default.

/**
 * Copies of one song. [similar] = at least one pair only has similar names
 * (rather than the same title and artist). [best] is the copy to keep if
 * one stands out (highest bitrate, then lossless), else null.
 */
data class DuplicateGroup(val tracks: List<Track>, val similar: Boolean, val best: String?)

/** [groups] to show, and the groups the user marked "Not duplicates" (for "Show dismissed"). */
data class DuplicateResult(val groups: List<DuplicateGroup>, val dismissed: List<DuplicateGroup>)

/** Exact: within 2 s, like the finder always did. Similar: within 3 s. */
const val EXACT_MAX_LENGTH_DIFF_MS = 2_000L
const val SIMILAR_MAX_LENGTH_DIFF_MS = 3_000L
/** Similar titles: at least this alike (1 = identical). */
const val TITLE_MIN = 0.85
/** ...and artists at least this alike, */
const val ARTIST_MIN = 0.80
/** ...or, if one artist is missing, titles at least this alike. */
const val TITLE_MIN_NO_ARTIST = 0.92

/** The key for one pair of songs, the same whichever way round: the smaller URI first. */
fun pairKey(a: String, b: String): String = if (a < b) "$a\u0000$b" else "$b\u0000$a"

/** Every pair among [uris]. */
fun pairsAmong(uris: Collection<String>): Set<String> {
    val list = uris.distinct()
    val pairs = HashSet<String>()
    for (i in list.indices) for (j in i + 1 until list.size) pairs += pairKey(list[i], list[j])
    return pairs
}

/**
 * "These songs aren't copies of the rest": the pairs among [selected], and
 * between [selected] and the other songs in [group].
 */
fun pairsForSelection(selected: Collection<String>, group: Collection<String>): Set<String> {
    val chosen = selected.toSet()
    val pairs = HashSet(pairsAmong(chosen))
    for (a in chosen) for (b in group) if (b !in chosen) pairs += pairKey(a, b)
    return pairs
}

/**
 * Finds groups of copies in [tracks]. Pairs in [dismissed] (see [pairKey])
 * are never joined, so a dismissed group falls apart; those pairs form the
 * result's dismissed groups instead.
 *
 * Fast enough for 10,000+ songs: songs are compared only with songs of
 * about the same length (a sliding window over the length-sorted list),
 * and most pairs are ruled out before the edit distance is computed.
 */
fun findDuplicates(tracks: List<Track>, dismissed: Set<String> = emptySet()): DuplicateResult {
    val songs = tracks.map(::SongKey)
    val kept = UnionFind(songs.size)
    val hidden = UnionFind(songs.size)
    val similarIn = BooleanArray(songs.size) // per union-find root, filled at the end
    val similarEdges = ArrayList<Pair<Int, Int>>()

    fun join(i: Int, j: Int, similar: Boolean) {
        if (pairKey(songs[i].track.uri, songs[j].track.uri) in dismissed) {
            hidden.union(i, j)
        } else {
            kept.union(i, j)
            if (similar) similarEdges += i to j
        }
    }

    // Exact: the finder's original key, any length (unknown lengths too).
    val byLegacyKey = songs.indices.groupBy { songs[it].legacyKey }
    for (indices in byLegacyKey.values) {
        if (indices.size < 2) continue
        for (a in indices.indices) for (b in a + 1 until indices.size) {
            val i = indices[a]
            val j = indices[b]
            if (isExact(songs[i], songs[j])) join(i, j, similar = false)
        }
    }

    // Similar: only songs with a known length, each compared with the next 3 s of songs.
    val byLength = songs.indices.filter { songs[it].track.durationMs > 0 }.sortedBy { songs[it].track.durationMs }
    for (a in byLength.indices) {
        val i = byLength[a]
        var b = a + 1
        while (b < byLength.size) {
            val j = byLength[b]
            if (songs[j].track.durationMs - songs[i].track.durationMs > SIMILAR_MAX_LENGTH_DIFF_MS) break
            if (songs[i].legacyKey != songs[j].legacyKey || !isExact(songs[i], songs[j])) {
                if (isSimilar(songs[i], songs[j])) join(i, j, similar = true)
            }
            b++
        }
    }

    for ((i, _) in similarEdges) similarIn[kept.find(i)] = true
    return DuplicateResult(
        groups = collectGroups(songs, kept) { root -> similarIn[root] },
        dismissed = collectGroups(songs, hidden) { false },
    )
}

/** Same title and artist the way the finder always compared them, and lengths within 2 s. */
private fun isExact(a: SongKey, b: SongKey): Boolean =
    a.legacyKey == b.legacyKey &&
        abs(a.track.durationMs - b.track.durationMs) <= EXACT_MAX_LENGTH_DIFF_MS &&
        a.markers == b.markers

private fun isSimilar(a: SongKey, b: SongKey): Boolean {
    if (a.markers != b.markers) return false
    if (a.title.isEmpty() || b.title.isEmpty()) return false
    val titleSimilarity = similarity(a.title, a.titleWords, b.title, b.titleWords, TITLE_MIN)
    if (titleSimilarity < TITLE_MIN) return false
    if (a.artistUnknown || b.artistUnknown) return titleSimilarity >= TITLE_MIN_NO_ARTIST
    return similarity(a.artist, a.artistWords, b.artist, b.artistWords, ARTIST_MIN) >= ARTIST_MIN
}

/**
 * How alike two names are, 0..1: 1 - edit distance / longer length, also
 * tried with the words sorted (word order doesn't matter); the higher of
 * the two. Returns 0 early once it can't reach [atLeast].
 */
internal fun similarity(a: String, aWords: String, b: String, bWords: String, atLeast: Double): Double {
    if (a == b || aWords == bWords) return 1.0
    val direct = similarity(a, b, atLeast)
    if (direct >= atLeast) return direct
    return max(direct, similarity(aWords, bWords, atLeast))
}

/** 1 - levenshtein / longer length, counted in code points; 0 if it would be below [atLeast]. */
internal fun similarity(a: String, b: String, atLeast: Double = 0.0): Double {
    val x = a.codePoints().toArray()
    val y = b.codePoints().toArray()
    val longer = max(x.size, y.size)
    if (longer == 0) return 1.0
    // The most edits that still reach atLeast; the length difference alone may already be too many.
    val allowed = ((1 - atLeast) * longer + 1e-9).toInt()
    if (abs(x.size - y.size) > allowed) return 0.0
    val distance = boundedLevenshtein(x, y, allowed) ?: return 0.0
    return 1.0 - distance.toDouble() / longer
}

/** Edit distance between [a] and [b], or null if it's more than [limit] (only a band of the table is filled). */
private fun boundedLevenshtein(a: IntArray, b: IntArray, limit: Int): Int? {
    val big = limit + 1
    var previous = IntArray(b.size + 1) { if (it <= limit) it else big }
    var current = IntArray(b.size + 1)
    for (i in 1..a.size) {
        val from = max(1, i - limit)
        val to = min(b.size, i + limit)
        current.fill(big)
        current[0] = if (i <= limit) i else big
        var rowMin = current[0]
        for (j in from..to) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            val value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
            current[j] = min(value, big)
            if (current[j] < rowMin) rowMin = current[j]
        }
        if (rowMin > limit) return null
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.size].takeIf { it <= limit }
}

/** Everything about one song the matcher needs, worked out once. */
private class SongKey(val track: Track) {
    /** The finder's original key: lowercase, bracketed parts removed, letters and digits only. */
    val legacyKey: String = legacyNormalized(track.title) + "|" + legacyNormalized(track.artist)
    /** Words that mean a different recording ("live", "remix"...), found before brackets are removed. */
    val markers: Set<String> = versionMarkers(track.title)
    val title: String = normalizeName(track.title, isTitle = true)
    val titleWords: String = sortedWords(title)
    val artist: String = normalizeName(track.artist, isTitle = false)
    val artistWords: String = sortedWords(artist)
    val artistUnknown: Boolean = artist.isEmpty() || artist == "unknown" || artist == "unknown artist" || artist == "<unknown>"
}

private val LEGACY_BRACKETED = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")

private fun legacyNormalized(text: String): String =
    LEGACY_BRACKETED.replace(text.lowercase(), "").filter { it.isLetterOrDigit() }

private val LEADING_TRACK_NUMBER = Regex("""^\d{1,3}\s*[-._)]\s*""")
private val BRACKETED = Regex("""[(\[（【][^)\]）】]*[)\]）】]""")
private val EDITION_SUFFIX = Regex(
    """\s+[-–—]\s+(\d{4}\s+)?(remaster(ed)?(\s+\d{4})?(\s+version)?|\d{4}\s+remaster(ed)?|deluxe(\s+edition)?|single version|radio edit|album version|mono|stereo)\s*$"""
)
private val FEATURING = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""")
private val NOT_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]+""")
private val COMBINING_MARKS = Regex("""\p{Mn}+""")

/**
 * A name as the similar-names check compares it: full-width letters made
 * normal, lowercase, Latin accents removed, "&" -> "and", a leading track
 * number, bracketed parts, a " - 2011 Remaster"-style suffix and "feat. X"
 * removed, punctuation dropped, spaces collapsed. CJK is kept as it is
 * (Simplified and Traditional variants aren't matched).
 */
internal fun normalizeName(text: String, isTitle: Boolean): String {
    var s = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    s = COMBINING_MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
    s = Normalizer.normalize(s, Normalizer.Form.NFC)
    s = s.replace("&", " and ").replace('_', ' ').trim()
    if (isTitle) s = LEADING_TRACK_NUMBER.replace(s, "")
    s = BRACKETED.replace(s, " ")
    s = s.replace(Regex("""\s+"""), " ").trim()
    s = EDITION_SUFFIX.replace(s, "")
    s = FEATURING.replace(s, "")
    s = s.replace("'", "").replace("’", "") // "don't" = "dont"
    return NOT_LETTER_OR_DIGIT.replace(s, " ").trim()
}

private fun sortedWords(name: String): String = name.split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")

private val LATIN_MARKERS = Regex("""\b(live|remix|mix|acoustic|instrumental|karaoke|demo|cover|edit|version|ver)\b""")
private val CJK_MARKERS = listOf("伴奏" to "instrumental", "現場" to "live", "现场" to "live", "翻唱" to "cover")

/** The words in [title] that mean a different recording. "Radio edit", "album/single version" don't count. */
internal fun versionMarkers(title: String): Set<String> {
    val s = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace("radio edit", " ")
        .replace("album version", " ")
        .replace("single version", " ")
        .replace(Regex("""remaster(ed)?\s+version"""), " ")
    val markers = LATIN_MARKERS.findAll(s).map { if (it.value == "ver") "version" else it.value }.toHashSet()
    for ((word, marker) in CJK_MARKERS) if (word in s) markers += marker
    return markers
}

private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
    }
}

/** Groups of 2+: Exact ("Same song") first, then A-Z by title. */
private fun collectGroups(songs: List<SongKey>, sets: UnionFind, isSimilar: (root: Int) -> Boolean): List<DuplicateGroup> =
    songs.indices.groupBy(sets::find)
        .filterValues { it.size > 1 }
        .map { (root, members) ->
            val groupTracks = members.map { songs[it].track }
            DuplicateGroup(groupTracks, isSimilar(root), bestCopy(groupTracks))
        }
        .sortedWith(compareBy<DuplicateGroup> { it.similar }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.tracks[0].title })

private val LOSSLESS = setOf("flac", "wav", "aiff", "aif", "alac", "ape", "wv")

/** The copy with the highest bitrate, then lossless before lossy; null if none stands out. */
private fun bestCopy(tracks: List<Track>): String? {
    fun lossless(track: Track) = track.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in LOSSLESS
    val order = compareByDescending<Track> { it.bitrateKbps }.thenByDescending { lossless(it) }
    val sorted = tracks.sortedWith(order)
    return sorted[0].uri.takeIf { order.compare(sorted[0], sorted[1]) != 0 }
}
