package com.biasnil.audioforge.lyrics

/** One timed lyric line; [words] has word-by-word timing ("enhanced" .lrc) if the file has it. */
data class SyncedLyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/** One word of an enhanced .lrc line: "<00:12.50>world". */
data class LyricWord(val timeMs: Long, val text: String)

/** What the Now Playing screen shows in its lyrics panel. */
sealed interface LyricsState {
    data object None : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data class Plain(val lines: List<String>) : LyricsState
    data class Synced(val lines: List<SyncedLyricLine>) : LyricsState
}

// Matches one [mm:ss], [mm:ss.x], [mm:ss.xx] or [mm:ss.xxx] tag; a line can
// have several run together ("[00:12.00][00:45.30]repeated words").
private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
// Word-level tags of enhanced .lrc: <mm:ss.xx>.
private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

private fun tagMs(minutes: String, seconds: String, fraction: String): Long {
    val fractionMs = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        else -> fraction.take(3).toLong()
    }
    return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
}

/**
 * Parses .lrc text into time-sorted lines -- one entry per timestamp, so a
 * line tagged at several times appears at each. Lines without a leading
 * time tag (plain text, [ar:...]-style metadata) are skipped. Word-level
 * <mm:ss.xx> tags give [SyncedLyricLine.words] (and are taken out of the
 * text); an [offset:ms] tag shifts everything (+ = earlier, as in the .lrc
 * format). An empty result means "not actually synced": show the text as
 * plain lyrics. Port of the desktop's ParseSyncedLyrics() + the iOS LRCParser.
 */
fun parseSyncedLyrics(lrcText: String): List<SyncedLyricLine> {
    val lines = ArrayList<SyncedLyricLine>()
    var offsetMs = 0L
    for (rawLine in lrcText.lineSequence()) {
        OFFSET_TAG.find(rawLine)?.let { offsetMs = it.groupValues[1].toLong() }
        val times = ArrayList<Long>()
        var consumedUpTo = 0
        for (match in TIME_TAG.findAll(rawLine)) {
            if (match.range.first != consumedUpTo) break // tags must run contiguously from the line start
            times += tagMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            consumedUpTo = match.range.last + 1
        }
        if (times.isEmpty()) continue
        val content = rawLine.substring(consumedUpTo)
        val text = content.replace(WORD_TAG, "").trim()
        val words = parseWords(content)
        times.forEach { lines += SyncedLyricLine(it, text, words) }
    }
    lines.sortBy { it.timeMs }
    if (offsetMs == 0L) return lines
    return lines.map { line ->
        line.copy(
            timeMs = (line.timeMs - offsetMs).coerceAtLeast(0),
            words = line.words.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0)) },
        )
    }
}

/** "<00:12.00>Hello <00:12.50>world" -> [(12000, "Hello "), (12500, "world")]. */
private fun parseWords(content: String): List<LyricWord> {
    val matches = WORD_TAG.findAll(content).toList()
    if (matches.isEmpty()) return emptyList()
    return matches.mapIndexedNotNull { index, match ->
        val start = match.range.last + 1
        val end = if (index + 1 < matches.size) matches[index + 1].range.first else content.length
        val word = content.substring(start, end)
        if (word.isEmpty()) null else LyricWord(tagMs(match.groupValues[1], match.groupValues[2], match.groupValues[3]), word)
    }
}

/** A line shows a moment before it's sung, like the iOS app. */
const val LYRICS_LEAD_MS = 150L

/** Index of the line being sung at [positionMs] (the last one that's started), or -1 before the first. */
fun currentLyricIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var result = -1
    val now = positionMs + LYRICS_LEAD_MS
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= now) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** Turns fetched/loaded text into what to show: synced if it has valid time tags, else plain lines. */
fun lyricsStateFor(text: String, maybeSynced: Boolean): LyricsState {
    if (text.isBlank()) return LyricsState.NotFound
    if (maybeSynced) {
        val synced = parseSyncedLyrics(text)
        if (synced.isNotEmpty()) return LyricsState.Synced(synced)
    }
    val plain = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    return if (plain.isEmpty()) LyricsState.NotFound else LyricsState.Plain(plain)
}

// --- LRCLIB search planning ------------------------------------------------

/** One LRCLIB search: by track/artist name, or LRCLIB's general [query] ("q") search. */
data class LyricsQuery(val trackName: String? = null, val artistName: String? = null, val query: String? = null)

private val LEADING_TRACK_NUMBER = Regex("""^\d{1,3}\s*[-._)]?\s+""")
// "(Live)", "[Remastered 2011]", "（伴奏版）", "【MV】"... -- ASCII and full-width brackets
private val BRACKETED = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
private val FEATURING = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

/**
 * The searches to try, most specific first, stopping at the first that
 * finds lyrics:
 *  1. title + artist as tagged (the desktop's only search);
 *  2. the title without "(Live)"/"[Remastered]"/"feat. X" decorations;
 *  3. LRCLIB's general search over title + artist together;
 *  4. for an untagged file (title is just the file name), "01 - Artist - Title"
 *     split into artist and title.
 */
fun lrclibQueries(title: String, artist: String, fileName: String): List<LyricsQuery> {
    val cleanTitle = title.trim()
    val cleanArtist = artist.trim()
    val queries = LinkedHashSet<LyricsQuery>()
    if (cleanTitle.isNotEmpty()) {
        queries += LyricsQuery(trackName = cleanTitle, artistName = cleanArtist.ifEmpty { null })
        val undecorated = cleanTitle.replace(BRACKETED, "").replace(FEATURING, "").trim()
        if (undecorated.isNotEmpty() && undecorated != cleanTitle) {
            queries += LyricsQuery(trackName = undecorated, artistName = cleanArtist.ifEmpty { null })
        }
        queries += LyricsQuery(query = listOf(undecorated.ifEmpty { cleanTitle }, cleanArtist).filter { it.isNotEmpty() }.joinToString(" "))
    }
    val fromFileName = cleanArtist.isEmpty() && cleanTitle == fileName.substringBeforeLast('.').trim()
    if (fromFileName) {
        val name = cleanTitle.replace(LEADING_TRACK_NUMBER, "")
        val parts = name.split(" - ", limit = 2).map { it.trim() }
        if (parts.size == 2 && parts.all { it.isNotEmpty() }) {
            queries += LyricsQuery(trackName = parts[1], artistName = parts[0])
        } else if (name.isNotEmpty() && name != cleanTitle) {
            queries += LyricsQuery(trackName = name)
        }
    }
    return queries.toList()
}

/** One LRCLIB search result, as far as choosing between them goes. */
data class LyricsCandidate(val durationSeconds: Double?, val synced: String, val plain: String)

/**
 * Picks the best result: among those whose length is within 5 s of the
 * song's (when both are known -- this weeds out live versions and covers),
 * synced lyrics first, then plain. Falls back to any result with lyrics if
 * none match the length. Returns (text, isSynced) or null.
 */
fun pickLyrics(candidates: List<LyricsCandidate>, trackDurationMs: Long): Pair<String, Boolean>? {
    val withLyrics = candidates.filter { it.synced.isNotBlank() || it.plain.isNotBlank() }
    if (withLyrics.isEmpty()) return null
    val durationMatched = if (trackDurationMs > 0) {
        withLyrics.filter { c -> c.durationSeconds != null && kotlin.math.abs(c.durationSeconds * 1000 - trackDurationMs) <= 5_000 }
    } else emptyList()
    for (pool in listOf(durationMatched, withLyrics)) {
        pool.firstOrNull { it.synced.isNotBlank() }?.let { return it.synced to true }
        pool.firstOrNull { it.plain.isNotBlank() }?.let { return it.plain to false }
    }
    return null
}
