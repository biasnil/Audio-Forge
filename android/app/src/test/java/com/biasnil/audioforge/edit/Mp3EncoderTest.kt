package com.biasnil.audioforge.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class Mp3EncoderTest {

    @Test
    fun encodesStereoIntoMp3FramesAtTheAskedBitrate() {
        val out = ByteArrayOutputStream()
        val rate = 44_100
        Mp3Encoder(rate, 2, 192, out).use { encoder ->
            // Two seconds of a 440 Hz tone, in chunks like the editor sends.
            val chunk = ShortArray(4096 * 2)
            var t = 0
            repeat(2 * rate / 4096 + 1) {
                for (i in 0 until 4096) {
                    val s = (sin(2 * PI * 440 * t++ / rate) * 12_000).roundToInt().toShort()
                    chunk[2 * i] = s
                    chunk[2 * i + 1] = s
                }
                encoder.encode(chunk, chunk.size)
            }
            encoder.finish()
        }
        val bytes = out.toByteArray()
        // MPEG-1 Layer III frame sync, and the bitrate index for 192 kbps (11).
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFA, bytes[1].toInt() and 0xFE)
        assertEquals(11, (bytes[2].toInt() and 0xF0) shr 4)
        // About 2 seconds at 192 kbps = 48 KB.
        assertTrue("${bytes.size} bytes", bytes.size in 44_000..56_000)
    }

    @Test
    fun keepsTheSongsBitrateWhenItCan() {
        assertEquals(320, Mp3Encoder.bitrateFor(0))
        assertEquals(64, Mp3Encoder.bitrateFor(64))
        assertEquals(128, Mp3Encoder.bitrateFor(127))
        assertEquals(256, Mp3Encoder.bitrateFor(256))
        assertEquals(320, Mp3Encoder.bitrateFor(300))
        assertEquals(320, Mp3Encoder.bitrateFor(999))
    }
}
