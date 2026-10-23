package com.biasnil.audioforge.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {

    private fun run(left: Double, right: Double, mono: Boolean, balance: Float): DoubleArray =
        doubleArrayOf(left, right).also { applyChannels(it, mono, balance) }

    @Test
    fun neutralLeavesTheFrameAlone() {
        assertArrayEquals(doubleArrayOf(0.4, -0.2), run(0.4, -0.2, mono = false, balance = 0f), 0.0)
        assertTrue(ChannelSettings().isNeutral)
    }

    @Test
    fun monoGivesBothEarsTheMix() {
        assertArrayEquals(doubleArrayOf(0.3, 0.3), run(0.6, 0.0, mono = true, balance = 0f), 1e-12)
    }

    @Test
    fun balanceTurnsTheOtherSideDown() {
        assertArrayEquals(doubleArrayOf(0.5, 1.0), run(1.0, 1.0, mono = false, balance = 0.5f), 1e-6)
        assertArrayEquals(doubleArrayOf(1.0, 0.0), run(1.0, 1.0, mono = false, balance = -1f), 1e-6)
    }

    @Test
    fun monoSourcesAreLeftAlone() {
        val frame = doubleArrayOf(0.7)
        applyChannels(frame, mono = true, balance = 1f)
        assertArrayEquals(doubleArrayOf(0.7), frame, 0.0)
    }

    @Test
    fun settingsClampTheBalance() {
        val settings = ChannelSettings().apply { update(mono = false, balance = 3f) }
        assertTrue(settings.balance == 1f)
        assertFalse(settings.isNeutral)
    }
}
