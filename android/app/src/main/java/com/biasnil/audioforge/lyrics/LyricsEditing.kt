package com.biasnil.audioforge.lyrics

import java.util.Locale

/** A lyric line in the editor; [timeMs] is null until it's been timed (plain lyrics start that way). */
data class EditLine(val timeMs: Long?, val text: String, val words: List<LyricWord> = emptyList())

/**
 * The lines to edit: synced lyrics with the song's sync offset already
 * applied (so what's saved needs no offset), or plain lyrics, untimed.
 */
fun editLinesFrom(state: LyricsState, offsetMs: Long): List<EditLine> = when (state) {
    is LyricsState.Synced -> state.lines.map { line ->
        EditLine(
            timeMs = (line.timeMs + offsetMs).coerceAtLeast(0),
            text = line.text,
            words = line.words.map { it.copy(timeMs = (it.timeMs + offsetMs).coerceAtLeast(0)) },
        )
    }
    is LyricsState.Plain -> state.lines.map { EditLine(null, it) }
    else -> emptyList()
}

/** Line [index] starts at [timeMs]; its words move with it, keeping their spacing. */
fun List<EditLine>.withTime(index: Int, timeMs: Long): List<EditLine> = mapIndexed { i, line ->
    if (i != index) line else {
        val newTime = timeMs.coerceAtLeast(0)
        val delta = newTime - (line.timeMs ?: newTime)
        line.copy(timeMs = newTime, words = line.words.map { it.copy(timeMs = (it.timeMs + delta).coerceAtLeast(0)) })
    }
}

/** Every timed line (and word) moved by [deltaMs]: the whole song's lyrics earlier or later. */
fun List<EditLine>.shiftedBy(deltaMs: Long): List<EditLine> = map { line ->
    line.copy(
        timeMs = line.timeMs?.let { (it + deltaMs).coerceAtLeast(0) },
        words = line.words.map { it.copy(timeMs = (it.timeMs + deltaMs).coerceAtLeast(0)) },
    )
}

/**
 * .lrc text for [lines]. A line not timed yet takes the time of the timed
 * line before it (or 0), so nothing is lost; word timings are kept
 * (enhanced .lrc). Times are [mm:ss.xx].
 */
fun formatLrc(lines: List<EditLine>, title: String = "", artist: String = ""): String = buildString {
    if (title.isNotBlank()) append("[ti:").append(title).append("]\n")
    if (artist.isNotBlank()) append("[ar:").append(artist).append("]\n")
    var last = 0L
    for (line in lines) {
        val time = line.timeMs ?: last
        last = time
        append(lrcTag('[', time, ']'))
        if (line.words.isEmpty()) {
            append(line.text)
        } else {
            // Each word keeps the space after it, as it was read.
            line.words.forEach { word ->
                append(lrcTag('<', word.timeMs, '>'))
                append(word.text)
            }
        }
        append('\n')
    }
}

/** "[01:02.35]" */
fun lrcTag(open: Char, timeMs: Long, close: Char): String {
    val centis = (timeMs.coerceAtLeast(0) + 5) / 10
    return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, centis / 6000, centis / 100 % 60, centis % 100, close)
}
