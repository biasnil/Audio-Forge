package com.biasnil.audioforge.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitPerfectTest {

    private val dac = listOf(
        MixerOption(sampleRate = 48_000, channels = 2, bits = 16, bitPerfect = false),
        MixerOption(sampleRate = 44_100, channels = 2, bits = 24, bitPerfect = true),
        MixerOption(sampleRate = 44_100, channels = 2, bits = 16, bitPerfect = true),
        MixerOption(sampleRate = 96_000, channels = 2, bits = 16, bitPerfect = true),
    )

    @Test
    fun picksTheExactSixteenBitMatch() {
        assertEquals(dac[2], chooseBitPerfect(dac, 44_100, 2))
        assertEquals(dac[3], chooseBitPerfect(dac, 96_000, 2))
    }

    @Test
    fun noMatchMeansNormalOutput() {
        assertNull(chooseBitPerfect(dac, 48_000, 2)) // offered, but not bit-perfect
        assertNull(chooseBitPerfect(dac, 44_100, 1)) // mono song
        assertNull(chooseBitPerfect(dac, 88_200, 2))
        assertNull(chooseBitPerfect(emptyList(), 44_100, 2))
    }
}
