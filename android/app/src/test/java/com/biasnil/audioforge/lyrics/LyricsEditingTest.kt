package com.biasnil.audioforge.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEditingTest {

    @Test
    fun syncOffsetIsBakedIn() {
        val state = LyricsState.Synced(listOf(SyncedLyricLine(1_000, "a"), SyncedLyricLine(2_000, "b")))
        assertEquals(listOf(1_500L, 2_500L), editLinesFrom(state, 500).map { it.timeMs })
    }

    @Test
    fun plainLyricsStartUntimed() {
        val lines = editLinesFrom(LyricsState.Plain(listOf("one", "two")), 0)
        assertEquals(listOf(null, null), lines.map { it.timeMs })
    }

    @Test
    fun settingALineMovesItsWordsWithIt() {
        val lines = listOf(EditLine(1_000, "Hello world", listOf(LyricWord(1_000, "Hello "), LyricWord(1_400, "world"))))
        val moved = lines.withTime(0, 2_000)
        assertEquals(2_000L, moved[0].timeMs)
        assertEquals(listOf(2_000L, 2_400L), moved[0].words.map { it.timeMs })
    }

    @Test
    fun shiftingNeverGoesBelowZero() {
        val lines = listOf(EditLine(30, "a"), EditLine(null, "b"))
        assertEquals(listOf(0L, null), lines.shiftedBy(-50).map { it.timeMs })
    }

    @Test
    fun writesLrcThatReadsBackTheSame() {
        val lines = listOf(
            EditLine(1_230, "First"),
            EditLine(null, "Untimed takes the time before"),
            EditLine(62_350, "Hello world", listOf(LyricWord(62_350, "Hello "), LyricWord(62_800, "world"))),
        )
        val lrc = formatLrc(lines, title = "Song", artist = "Band")
        assertEquals(
            "[ti:Song]\n[ar:Band]\n[00:01.23]First\n[00:01.23]Untimed takes the time before\n[01:02.35]<01:02.35>Hello <01:02.80>world\n",
            lrc,
        )
        val parsed = parseSyncedLyrics(lrc)
        assertEquals(listOf(1_230L, 1_230L, 62_350L), parsed.map { it.timeMs })
        assertEquals("Hello world", parsed[2].text)
        assertEquals(listOf(62_350L, 62_800L), parsed[2].words.map { it.timeMs })
    }
}
