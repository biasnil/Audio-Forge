package com.biasnil.audioforge.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveViewTest {
    private val whole = WaveView.whole(total = 10_000, minFrames = 100)

    @Test
    fun zoomingKeepsTheFocusInPlace() {
        // Zoom in 4x around frame 2500 (a quarter of the way in): it stays a quarter of the way across.
        val zoomed = whole.zoomed(4f, 2_500)
        assertEquals(2_500, zoomed.frames)
        assertEquals(1_875, zoomed.start)
        assertEquals(0.25, (2_500 - zoomed.start).toDouble() / zoomed.frames, 0.001)
    }

    @Test
    fun zoomStopsAtTheLimits() {
        assertEquals(100, whole.zoomed(1_000f, 5_000).frames)
        assertEquals(10_000, whole.zoomed(4f, 0).zoomed(0.01f, 0).frames)
    }

    @Test
    fun scrollingStaysInsideTheSong() {
        val zoomed = whole.zoomed(10f, 0)
        assertEquals(0, zoomed.scrolled(-500).start)
        assertEquals(9_000, zoomed.scrolled(50_000).start)
    }

    @Test
    fun followsThePlayhead() {
        val zoomed = whole.zoomed(10f, 0) // 0..1000
        assertEquals(zoomed, zoomed.showing(500))
        assertEquals(1_100, zoomed.showing(2_000).start) // 2000 shown with a 100-frame margin
    }

    @Test
    fun aCutShortensTheView() {
        assertEquals(6_000, whole.forTotal(6_000).frames)
    }
}
