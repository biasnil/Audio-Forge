package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.ReverbPreset
import com.biasnil.audioforge.data.SurroundPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialDspTest {

    private fun dsp(params: SpatialParams, channels: Int = 2): SpatialDsp {
        val settings = SpatialSettings().apply { update(params) }
        return SpatialDsp(settings).apply { configure(SAMPLE_RATE, channels) }
    }

    /** Feeds [frames] frames (the first one [left]/[right], the rest silence) and returns the outputs. */
    private fun SpatialDsp.impulse(left: Double, right: Double, frames: Int): Pair<DoubleArray, DoubleArray> {
        val outL = DoubleArray(frames)
        val outR = DoubleArray(frames)
        for (i in 0 until frames) {
            if (i == 0) process(left, right) else process(0.0, 0.0)
            outL[i] = outLeft
            outR[i] = outRight
        }
        return outL to outR
    }

    @Test
    fun defaultsAreInactive() {
        assertTrue(SpatialParams().isNeutral)
        assertFalse(dsp(SpatialParams()).isActive)
        // A preset with nothing mixed in does nothing either.
        assertFalse(dsp(SpatialParams(surround = SurroundPreset.Hall, surroundAmount = 0f)).isActive)
        assertFalse(dsp(SpatialParams(reverb = ReverbPreset.Plate, reverbMix = 0f)).isActive)
    }

    @Test
    fun monoSourcesAreLeftAlone() {
        assertFalse(dsp(SpatialParams(reverb = ReverbPreset.Hall), channels = 1).isActive)
    }

    @Test
    fun zeroWidthIsMono() {
        val dsp = dsp(SpatialParams(width = 0f))
        assertTrue(dsp.isActive)
        dsp.process(0.8, -0.2)
        assertEquals(0.3, dsp.outLeft, 1e-12)
        assertEquals(0.3, dsp.outRight, 1e-12)
    }

    @Test
    fun widerWidthKeepsTheMiddle() {
        val dsp = dsp(SpatialParams(width = 2f))
        dsp.process(0.5, 0.5) // centred: nothing to widen
        assertEquals(0.5, dsp.outLeft, 1e-12)
        assertEquals(0.5, dsp.outRight, 1e-12)
        dsp.process(0.4, 0.0)
        assertEquals(0.6, dsp.outLeft, 1e-12)
        assertEquals(-0.2, dsp.outRight, 1e-12)
    }

    @Test
    fun surroundLetsTheOtherEarHearEachSide() {
        val (outL, outR) = dsp(SpatialParams(surround = SurroundPreset.Room, surroundAmount = 1f))
            .impulse(1.0, 0.0, SAMPLE_RATE / 10)
        assertTrue(outL[0] > 0.5) // the dry sound, a little quieter
        assertEquals(0.0, outR[0], 1e-12) // not instantly...
        assertTrue(outR.drop(1).take(SAMPLE_RATE / 100).any { abs(it) > 1e-3 }) // ...but within 10 ms
    }

    @Test
    fun reverbTailRingsThenDiesAway() {
        val dsp = dsp(SpatialParams(reverb = ReverbPreset.Hall, reverbMix = 1f))
        val (outL, outR) = dsp.impulse(1.0, 1.0, SAMPLE_RATE * 8)
        val tail = (SAMPLE_RATE / 10 until SAMPLE_RATE / 2).maxOf { abs(outL[it]) + abs(outR[it]) }
        assertTrue("tail after 0.1 s: $tail", tail > 1e-4)
        assertTrue((0 until SAMPLE_RATE * 8).all { abs(outL[it]) < 1.0 && abs(outR[it]) < 1.0 })
        val end = (SAMPLE_RATE * 7 until SAMPLE_RATE * 8).maxOf { abs(outL[it]) }
        assertTrue("still ringing after 7 s: $end", end < 1e-3)
    }

    @Test
    fun resetClearsTheTail() {
        val dsp = dsp(SpatialParams(reverb = ReverbPreset.LargeRoom, reverbMix = 1f))
        dsp.impulse(1.0, 1.0, SAMPLE_RATE / 20)
        dsp.reset()
        val (outL, outR) = dsp.impulse(0.0, 0.0, SAMPLE_RATE)
        assertTrue(outL.all { it == 0.0 } && outR.all { it == 0.0 })
    }

    @Test
    fun settingsChangesArePickedUp() {
        val settings = SpatialSettings()
        val dsp = SpatialDsp(settings).apply { configure(SAMPLE_RATE, 2) }
        assertFalse(dsp.isActive)
        settings.update(SpatialParams(width = 0.5f))
        dsp.refresh()
        assertTrue(dsp.isActive)
    }

    @Test
    fun everyPresetStaysStableAtOtherSampleRates() {
        for (rate in intArrayOf(22_050, 48_000, 96_000)) {
            for (surround in SurroundPreset.entries) for (reverb in ReverbPreset.entries) {
                val settings = SpatialSettings().apply {
                    update(SpatialParams(surround = surround, surroundAmount = 1f, reverb = reverb, reverbMix = 1f))
                }
                val dsp = SpatialDsp(settings).apply { configure(rate, 2) }
                var peak = 0.0
                for (i in 0 until rate) {
                    dsp.process(if (i % 200 == 0) 0.9 else 0.0, if (i % 300 == 0) -0.9 else 0.0)
                    peak = maxOf(peak, abs(dsp.outLeft), abs(dsp.outRight))
                }
                assertTrue("$surround/$reverb at $rate Hz: $peak", peak.isFinite() && peak < 4.0)
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
