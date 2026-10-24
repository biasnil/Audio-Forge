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
    fun cutPartsCanBePutBack() {
        val edit = AudioEdit.whole(1000).keepOnly(100, 900).cut(200, 300)
        // Trimmed start, the cut in the middle, and the trimmed end.
        val gaps = edit.gaps(1000)
        assertEquals(listOf(Gap(0, Span(0, 100)), Gap(200, Span(300, 400)), Gap(700, Span(900, 1000))), gaps)
        assertEquals(listOf(Span(100, 900)), edit.restore(gaps[1]).spans)
        assertEquals(AudioEdit.whole(1000), gaps.fold(edit) { e, gap -> e.restore(gap) })
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
    fun flacLengthGoesIntoStreamInfo() {
        val file = java.io.File.createTempFile("streaminfo", ".flac")
        try {
            // "fLaC", STREAMINFO header, then 34 bytes: 44.1 kHz, 2 channels, 16 bits, length 0.
            val info = ByteArray(34)
            info[10] = 0x0A; info[11] = 0xC4.toByte(); info[12] = 0x42; info[13] = 0xF0.toByte()
            file.writeBytes("fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + info)
            EditRenderer.setFlacTotalSamples(file, 0x1_2345_6789L)
            val bytes = file.readBytes()
            // Bits per sample (top of byte 13) untouched; the 36-bit count after it.
            assertEquals(0xF1, bytes[8 + 13].toInt() and 0xFF)
            assertEquals(0x23456789, java.nio.ByteBuffer.wrap(bytes, 8 + 14, 4).int)
        } finally {
            file.delete()
        }
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
