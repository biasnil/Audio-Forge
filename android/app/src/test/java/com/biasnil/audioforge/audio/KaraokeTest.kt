package com.biasnil.audioforge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class KaraokeTest {

    private fun dsp(amount: Float, channels: Int = 2) =
        KaraokeDsp(KaraokeSettings().apply { update(amount) }).apply { configure(SAMPLE_RATE, channels) }

    /** RMS of the left output, over the second half of 1 s of [left]/[right] tones. */
    private fun KaraokeDsp.rms(left: (Int) -> Double, right: (Int) -> Double): Double {
        var power = 0.0
        for (n in 0 until SAMPLE_RATE) {
            process(left(n), right(n))
            if (n >= SAMPLE_RATE / 2) power += outLeft * outLeft
        }
        return sqrt(power / (SAMPLE_RATE / 2))
    }

    private fun tone(hz: Double): (Int) -> Double = { n -> 0.5 * sin(2 * PI * hz * n / SAMPLE_RATE) }

    @Test
    fun offOrMonoDoesNothing() {
        assertFalse(dsp(0f).isActive)
        assertFalse(dsp(1f, channels = 1).isActive)
        assertTrue(dsp(0.5f).isActive)
    }

    @Test
    fun aCentredVoiceIsRemoved() {
        val voice = tone(1000.0) // same in both ears: the middle
        val before = dsp(0.001f).rms(voice, voice)
        val after = dsp(1f).rms(voice, voice)
        assertTrue("before $before, after $after", after < before * 0.1)
    }

    @Test
    fun centredBassAndSidesAreKept() {
        val bass = tone(50.0)
        val bassBefore = dsp(0.001f).rms(bass, bass)
        assertEquals(bassBefore, dsp(1f).rms(bass, bass), bassBefore * 0.15)

        val guitar = tone(1000.0) // only in the left ear: not the voice
        val silence: (Int) -> Double = { 0.0 }
        val sideBefore = dsp(0.001f).rms(guitar, silence)
        val sideAfter = dsp(1f).rms(guitar, silence)
        // Half of a one-sided sound is "middle", so at most half of it goes.
        assertTrue("before $sideBefore, after $sideAfter", sideAfter > sideBefore * 0.45)
    }

    @Test
    fun keyChangeFactors() {
        assertEquals(1f, pitchFactor(0), 0f)
        assertEquals(2f, pitchFactor(12), 1e-6f)
        assertEquals(0.5f, pitchFactor(-12), 1e-6f)
        assertEquals(1.0595f, pitchFactor(1), 1e-4f)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
