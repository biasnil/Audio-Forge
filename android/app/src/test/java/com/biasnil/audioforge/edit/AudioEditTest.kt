package com.biasnil.audioforge.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEditTest {

    @Test
    fun cuttingClosesTheGap() {
        val edit = AudioEdit.whole(1000).cut(200, 300)
        assertEquals(listOf(Span(0, 200), Span(300, 1000)), edit.spans)
        assertEquals(900, edit.length)
        // After the cut, frame 200 of the song is what was frame 300.
        assertEquals(300, edit.toSource(200))
        assertEquals(199, edit.toSource(199))
    }

    @Test
    fun cutsAcrossEarlierCuts() {
        // 0-200 and 300-1000 are left; cut 150..250 of that (150-200 and 300-350 of the song).
        val edit = AudioEdit.whole(1000).cut(200, 300).cut(150, 250)
        assertEquals(listOf(Span(0, 150), Span(350, 1000)), edit.spans)
    }

    @Test
    fun keepOnlyTrimsBothEnds() {
        val edit = AudioEdit.whole(1000).keepOnly(100, 900)
        assertEquals(listOf(Span(100, 900)), edit.spans)
        assertEquals(100, edit.toSource(0))
    }

    @Test
    fun emptyOrOutOfRangeSelectionsChangeNothing() {
        val whole = AudioEdit.whole(1000)
        assertEquals(whole, whole.cut(500, 500))
        assertEquals(whole, whole.cut(600, 400))
        assertEquals(listOf(Span(0, 900)), whole.cut(900, 5000).spans)
    }

    @Test
    fun playingFromTheMiddleOfAnEdit() {
        val edit = AudioEdit.whole(1000).cut(200, 300)
        assertEquals(listOf(Span(150, 200), Span(300, 1000)), edit.spansFrom(150))
        assertEquals(listOf(Span(400, 1000)), edit.spansFrom(300))
    }

    @Test
    fun changedOnlyWhenSomethingWasEdited() {
        assertFalse(AudioEdit.whole(1000).changed)
        assertTrue(AudioEdit.whole(1000).cut(0, 10).changed)
        assertTrue(AudioEdit.whole(1000).copy(gainDb = 2f).changed)
    }

    @Test
    fun normalizeBringsThePeakToMinusOneDb() {
        assertEquals(0f, normalizeGainDb(0.891f), 0.05f)
        assertEquals(6.0f, normalizeGainDb(0.4466f), 0.05f)
        // Never past the slider's range, and nothing for silence.
        assertEquals(AudioEdit.MAX_GAIN_DB, normalizeGainDb(0.001f))
        assertEquals(0f, normalizeGainDb(0f))
    }

    @Test
    fun wavHeaderDescribesTheData() {
        val header = EditRenderer.wavHeader(44_100, 2, 1_000)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals(44, header.size)
        // Little-endian data size at the end.
        assertEquals(1_000, (header[40].toInt() and 0xFF) or ((header[41].toInt() and 0xFF) shl 8))
    }
}
