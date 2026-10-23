package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.HeadphoneProfile
import com.biasnil.audioforge.data.ParametricFilter
import com.biasnil.audioforge.data.ParametricFilter.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class CorrectionDspTest {

    private fun dsp(preampDb: Double, vararg filters: ParametricFilter): CorrectionDsp {
        val settings = CorrectionSettings().apply {
            update(HeadphoneProfile("Test", "src", "over-ear", preampDb, filters.toList()))
        }
        return CorrectionDsp(settings).apply { configure(SAMPLE_RATE, 1) }
    }

    /** How much louder (dB) a steady sine at [hz] comes out. */
    private fun CorrectionDsp.gainAt(hz: Double): Double {
        reset()
        val total = SAMPLE_RATE // 1 s; the first half lets the filters settle
        var inPower = 0.0
        var outPower = 0.0
        for (n in 0 until total) {
            val x = 0.1 * sin(2 * PI * hz * n / SAMPLE_RATE)
            val y = process(x, 0)
            if (n >= total / 2) {
                inPower += x * x
                outPower += y * y
            }
        }
        return 20 * log10(sqrt(outPower / inPower))
    }

    @Test
    fun offWhenNoProfile() {
        assertFalse(CorrectionDsp(CorrectionSettings()).apply { configure(SAMPLE_RATE, 2) }.isActive)
    }

    @Test
    fun peakBoostsItsFrequencyOnly() {
        val dsp = dsp(0.0, ParametricFilter(Type.Peaking, 1000.0, 6.0, 1.0))
        assertTrue(dsp.isActive)
        assertEquals(6.0, dsp.gainAt(1000.0), 0.1)
        assertEquals(0.0, dsp.gainAt(50.0), 0.3)
        assertEquals(0.0, dsp.gainAt(15000.0), 0.3)
    }

    @Test
    fun shelvesLiftOneEnd() {
        val low = dsp(0.0, ParametricFilter(Type.LowShelf, 105.0, 6.0, 0.7))
        assertEquals(6.0, low.gainAt(25.0), 0.5)
        assertEquals(0.0, low.gainAt(5000.0), 0.2)
        val high = dsp(0.0, ParametricFilter(Type.HighShelf, 10000.0, -4.0, 0.7))
        assertEquals(-4.0, high.gainAt(19000.0), 0.6)
        assertEquals(0.0, high.gainAt(200.0), 0.2)
    }

    @Test
    fun preampTurnsEverythingDown() {
        val dsp = dsp(-6.0, ParametricFilter(Type.Peaking, 1000.0, 6.0, 1.0))
        assertEquals(0.0, dsp.gainAt(1000.0), 0.1)
        assertEquals(-6.0, dsp.gainAt(100.0), 0.3)
    }

    @Test
    fun filtersAboveNyquistAreSkipped() {
        val settings = CorrectionSettings().apply {
            update(HeadphoneProfile("Test", "src", "in-ear", 0.0, listOf(ParametricFilter(Type.Peaking, 30000.0, 6.0, 1.0))))
        }
        val dsp = CorrectionDsp(settings).apply { configure(SAMPLE_RATE, 1) }
        assertTrue(dsp.isActive)
        assertEquals(0.5, dsp.process(0.5, 0), 1e-12)
    }

    @Test
    fun aNewProfileIsPickedUp() {
        val settings = CorrectionSettings()
        val dsp = CorrectionDsp(settings).apply { configure(SAMPLE_RATE, 1) }
        settings.update(HeadphoneProfile("Test", "src", "in-ear", -3.0, listOf(ParametricFilter(Type.Peaking, 1000.0, 3.0, 1.0))))
        dsp.refresh()
        assertTrue(dsp.isActive)
        settings.update(null)
        dsp.refresh()
        assertFalse(dsp.isActive)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
