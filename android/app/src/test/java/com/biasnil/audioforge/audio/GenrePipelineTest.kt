package com.biasnil.audioforge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class GenrePipelineTest {

    // --- Window ---------------------------------------------------------------------

    @Test fun unknownLengthIsTheFirst30s() = assertEquals(GenreWindow(0, 30_000), GenreWindow.forDuration(0))

    @Test fun underFiveSecondsIsTooShort() {
        assertNull(GenreWindow.forDuration(3_000))
        assertNull(GenreWindow.forDuration(4_999))
    }

    @Test fun shortSongsAreHeardWhole() {
        assertEquals(GenreWindow(0, 5_000), GenreWindow.forDuration(5_000))
        assertEquals(GenreWindow(0, 40_000), GenreWindow.forDuration(40_000))
    }

    @Test fun longerSongsFromAQuarterIn() {
        assertEquals(GenreWindow(50_000, 30_000), GenreWindow.forDuration(200_000))
        // A 3-hour podcast: 30 s from 45 minutes in, quickly.
        assertEquals(GenreWindow(2_700_000, 30_000), GenreWindow.forDuration(10_800_000))
        // Just over 40 s: the window still ends inside the song.
        val w = GenreWindow.forDuration(41_000)!!
        assertTrue(w.startMs + w.lengthMs <= 41_000)
    }

    // --- Low-pass filter -----------------------------------------------------------

    @Test fun kernelIsNormalisedAndSymmetric() {
        val k = lowPassKernel(7_200.0 / 44_100, 47)
        assertEquals(1.0, k.sum().toDouble(), 1e-4)
        for (i in k.indices) assertEquals(k[i], k[k.size - 1 - i], 1e-6f)
    }

    private fun gainAt(frequency: Double, rate: Int = 44_100): Double {
        val filter = LowPassFilter(rate, 7_200.0)
        val n = rate / 4
        var input = 0.0
        var output = 0.0
        for (i in 0 until n) {
            val x = sin(2 * PI * frequency * i / rate).toFloat()
            val y = filter.process(x)
            if (i > 100) { // past the filter's start-up
                input += x * x
                output += y * y
            }
        }
        return sqrt(output / input)
    }

    @Test fun passesWhatYamnetHears() {
        assertTrue(abs(gainAt(1_000.0) - 1) < 0.02)
        assertTrue(gainAt(5_000.0) > 0.9)
    }

    @Test fun stopsWhatWouldFoldBackAt16kHz() {
        // 8-14 kHz would alias into YAMNet's 2-8 kHz after down-sampling to 16 kHz.
        assertTrue("10 kHz gain ${gainAt(10_000.0)}", gainAt(10_000.0) < 0.05)
        assertTrue("12 kHz gain ${gainAt(12_000.0)}", gainAt(12_000.0) < 0.05)
        assertTrue(gainAt(10_000.0, rate = 48_000) < 0.05)
    }

    // --- Class map ------------------------------------------------------------------

    @Test fun displayNameParsing() {
        assertEquals("Speech", GenreClassifier.displayName("0,/m/09x0r,Speech"))
        assertEquals("Child speech, kid speaking", GenreClassifier.displayName("1,/m/0ytgt,\"Child speech, kid speaking\""))
    }
}
