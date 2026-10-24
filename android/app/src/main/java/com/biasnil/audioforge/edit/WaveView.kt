package com.biasnil.audioforge.edit

/**
 * The part of the waveform on screen: [frames] frames from [start], on the
 * edited timeline of [total] frames. Never narrower than [minFrames] (the
 * most zoomed in) nor wider than the song.
 */
data class WaveView(val start: Long, val frames: Long, val total: Long, val minFrames: Long) {
    val end: Long get() = start + frames

    /** Zoomed by [factor] (2 = twice as close) around [focus], which stays where it is on screen. */
    fun zoomed(factor: Float, focus: Long): WaveView {
        if (factor <= 0f) return this
        val newFrames = (frames / factor).toLong().coerceIn(minOf(minFrames, total), total.coerceAtLeast(1))
        val focusShare = if (frames > 0) (focus - start).toDouble() / frames else 0.5
        return copy(frames = newFrames).at((focus - focusShare * newFrames).toLong())
    }

    /** Scrolled by [deltaFrames] (+ = later in the song). */
    fun scrolled(deltaFrames: Long): WaveView = at(start + deltaFrames)

    /** Scrolled just enough to show [position] (with a margin), or unchanged if it already shows. */
    fun showing(position: Long): WaveView {
        val margin = frames / 10
        return when {
            position < start + margin -> at(position - margin)
            position > end - margin -> at(position - frames + margin)
            else -> this
        }
    }

    /** Showing [from, to) with a little room around it. */
    fun around(from: Long, to: Long): WaveView {
        val span = ((to - from) * 1.2).toLong().coerceIn(minOf(minFrames, total), total.coerceAtLeast(1))
        return copy(frames = span).at(from - (span - (to - from)) / 2)
    }

    /** The same view for a song that's now [newTotal] frames long (after a cut or restore). */
    fun forTotal(newTotal: Long): WaveView = copy(total = newTotal, frames = frames.coerceIn(minOf(minFrames, newTotal), newTotal.coerceAtLeast(1))).at(start)

    private fun at(newStart: Long): WaveView = copy(start = newStart.coerceIn(0, (total - frames).coerceAtLeast(0)))

    companion object {
        /** The whole song on screen. */
        fun whole(total: Long, minFrames: Long) = WaveView(0, total.coerceAtLeast(1), total, minFrames)
    }
}
