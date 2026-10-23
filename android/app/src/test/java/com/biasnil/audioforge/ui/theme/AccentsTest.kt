package com.biasnil.audioforge.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentsTest {

    @Test
    fun textOnEveryAccentIsReadable() {
        for (seed in ACCENT_SWATCHES) for (dark in listOf(false, true)) {
            val tones = accentTones(seed, dark)
            val name = "%08X dark=%s".format(seed, dark)
            assertTrue("$name primary ${contrastRatio(tones.primary, tones.onPrimary)}",
                contrastRatio(tones.primary, tones.onPrimary) >= 4.5)
            assertTrue("$name container ${contrastRatio(tones.container, tones.onContainer)}",
                contrastRatio(tones.container, tones.onContainer) >= 4.5)
        }
    }

    @Test
    fun accentsKeepTheirHue() {
        // A red stays reddish and a green greenish once adjusted.
        val red = accentTones(0xFFE53935.toInt(), dark = false).primary
        assertTrue((red shr 16 and 0xFF) > (red shr 8 and 0xFF))
        val green = accentTones(0xFF43A047.toInt(), dark = true).primary
        assertTrue((green shr 8 and 0xFF) > (green shr 16 and 0xFF))
    }

    @Test
    fun pureBlackIsBlack() {
        assertEquals(0xFF000000.toInt(), darkBackground(pureBlack = true))
        assertNotEquals(0xFF000000.toInt(), darkBackground(pureBlack = false))
    }

    @Test
    fun contrastRatioEnds() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0)
    }
}
