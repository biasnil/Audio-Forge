package com.biasnil.audioforge.lyrics

/** One timed lyric line. */
data class SyncedLyricLine(val timeMs: Long, val text: String)

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

/**
 * Parses .lrc text into time-sorted lines -- one entry per timestamp, so a
 * line tagged at several times appears at each. Lines without a leading
 * time tag (plain text, [ar:...]-style metadata) are skipped. An empty
 * result means "not actually synced": show the text as plain lyrics.
 * Port of the desktop's ParseSyncedLyrics().
 */
fun parseSyncedLyrics(lrcText: String): List<SyncedLyricLine> {
    val lines = ArrayList<SyncedLyricLine>()
    for (rawLine in lrcText.lineSequence()) {
        val times = ArrayList<Long>()
        var consumedUpTo = 0
        for (match in TIME_TAG.findAll(rawLine)) {
            if (match.range.first != consumedUpTo) break // tags must run contiguously from the line start
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val fractionMs = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            times += minutes * 60_000 + seconds * 1_000 + fractionMs
            consumedUpTo = match.range.last + 1
        }
        if (times.isEmpty()) continue
        val text = rawLine.substring(consumedUpTo).trim()
        times.forEach { lines += SyncedLyricLine(it, text) }
    }
    lines.sortBy { it.timeMs }
    return lines
}

/** Index of the line being sung at [positionMs] (the last one that's started), or -1 before the first. */
fun currentLyricIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
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
