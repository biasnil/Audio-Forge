package com.biasnil.audioforge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ToneTest {

    private fun limiter(enabled: Boolean = true) = Limiter().apply {
        configure(SAMPLE_RATE, 2)
        this.enabled = enabled
    }

    /** Runs [input] (stereo frames) through and returns what comes out. */
    private fun Limiter.run(input: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val frame = DoubleArray(2)
        return input.map { (l, r) ->
            frame[0] = l
            frame[1] = r
            process(frame)
            frame[0] to frame[1]
        }
    }

    private fun sine(amplitude: Double, hz: Double, frames: Int) =
        List(frames) { n -> (amplitude * sin(2 * PI * hz * n / SAMPLE_RATE)).let { it to it } }

    @Test
    fun quietAudioPassesUntouched() {
        val input = sine(0.5, 440.0, 2000)
        val output = limiter().run(input)
        input.zip(output).forEach { (a, b) -> assertEquals(a.first, b.first, 1e-12) }
    }

    @Test
    fun loudAudioStaysUnderTheCeiling() {
        val input = sine(2.0, 440.0, SAMPLE_RATE / 2) // twice full scale, like 200% volume
        val output = limiter().run(input)
        assertTrue(output.all { abs(it.first) <= Limiter.CEILING + 1e-9 && abs(it.second) <= Limiter.CEILING + 1e-9 })
        // ...and it's turned down, not flattened: the peaks still reach near the ceiling.
        assertTrue(output.takeLast(SAMPLE_RATE / 10).maxOf { abs(it.first) } > 0.9)
    }

    @Test
    fun aSinglePeakIsCaughtThenReleasedSmoothly() {
        val input = List(SAMPLE_RATE) { if (it == 500) 3.0 to -3.0 else 0.2 to 0.2 }
        val output = limiter().run(input)
        assertTrue(output.all { abs(it.first) <= Limiter.CEILING + 1e-9 && abs(it.second) <= Limiter.CEILING + 1e-9 })
        assertEquals(0.2, output[499].first, 1e-12) // nothing before it
        assertEquals(Limiter.CEILING, output[500].first, 1e-9)
        assertTrue(output[501].first < 0.1) // still turned down just after...
        assertEquals(0.2, output.last().first, 1e-3) // ...and back to normal later
        // Rising back up, never jumping.
        assertTrue((502 until 5000).all { output[it].first >= output[it - 1].first - 1e-12 })
    }

    @Test
    fun offLetsPeaksThroughForTheClipper() {
        val output = limiter(enabled = false).run(listOf(1.5 to 1.5))
        assertEquals(1.5, output[0].first, 1e-12)
    }

    private fun tone(params: ToneParams) = ToneDsp(ToneSettings().apply { update(params) }).apply { configure(SAMPLE_RATE, 1) }

    private fun ToneDsp.gainAt(hz: Double): Double {
        reset()
        var inPower = 0.0
        var outPower = 0.0
        for (n in 0 until SAMPLE_RATE) {
            val x = 0.1 * sin(2 * PI * hz * n / SAMPLE_RATE)
            val y = process(x, 0)
            if (n >= SAMPLE_RATE / 2) {
                inPower += x * x
                outPower += y * y
            }
        }
        return 20 * log10(sqrt(outPower / inPower))
    }

    @Test
    fun bassBoostLiftsTheLowEnd() {
        val dsp = tone(ToneParams(bassBoostDb = 6f))
        assertTrue(dsp.isActive)
        assertEquals(6.0, dsp.gainAt(30.0), 0.5)
        assertEquals(0.0, dsp.gainAt(3000.0), 0.2)
    }

    @Test
    fun loudnessGrowsAsTheVolumeDrops() {
        assertEquals(0f, ToneParams.loudnessAt(1f))
        assertEquals(1f, ToneParams.loudnessAt(0f))
        assertFalse(tone(ToneParams(loudness = ToneParams.loudnessAt(1f))).isActive)
        val quiet = tone(ToneParams(loudness = 1f))
        assertEquals(ToneParams.LOUDNESS_BASS_DB, quiet.gainAt(30.0), 0.6)
        assertEquals(ToneParams.LOUDNESS_TREBLE_DB, quiet.gainAt(18000.0), 0.6)
        assertEquals(0.0, quiet.gainAt(1000.0), 0.5)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
