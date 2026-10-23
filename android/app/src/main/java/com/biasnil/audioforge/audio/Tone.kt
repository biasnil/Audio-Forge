package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.ParametricFilter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Bass boost and loudness, as the shelves they add. */
data class ToneParams(
    /** 0 = off. */
    val bassBoostDb: Float = 0f,
    /** 0..1: how much the loudness contour applies (more at lower phone volume); 0 = off. */
    val loudness: Float = 0f,
) {
    val bassDb: Double get() = bassBoostDb + LOUDNESS_BASS_DB * loudness.toDouble()
    val trebleDb: Double get() = LOUDNESS_TREBLE_DB * loudness.toDouble()
    val isNeutral: Boolean get() = abs(bassDb) < 0.05 && abs(trebleDb) < 0.05

    companion object {
        const val MAX_BASS_BOOST_DB = 12f
        /** At the lowest volume: ears lose bass first, then treble (the equal-loudness contours). */
        const val LOUDNESS_BASS_DB = 8.0
        const val LOUDNESS_TREBLE_DB = 4.0
        const val BASS_HZ = 100.0
        const val TREBLE_HZ = 8000.0
        const val SHELF_Q = 0.7

        /**
         * The loudness amount at a phone volume of [fraction] (0..1): none at
         * full volume, rising as it's turned down.
         */
        fun loudnessAt(fraction: Float): Float = (1f - fraction.coerceIn(0f, 1f))
    }
}

/** Shared by both players' processors like [EqualizerSettings]. */
class ToneSettings {
    @Volatile
    var params = ToneParams()
        private set

    fun update(params: ToneParams) {
        this.params = params
    }
}

/** A low shelf for bass, a high shelf for treble. No Android/Media3 types, so it's unit testable. */
class ToneDsp(private val settings: ToneSettings) {
    private val chain = ParametricChain()
    private var applied: ToneParams? = null

    var isActive = false
        private set

    fun configure(sampleRate: Int, channels: Int) {
        chain.configure(sampleRate, channels)
        applied = null
        refresh()
    }

    fun reset() = chain.reset()

    fun refresh() {
        val params = settings.params
        if (params == applied || !chain.isConfigured) return
        applied = params
        isActive = !params.isNeutral
        chain.setFilters(
            if (isActive) listOf(
                ParametricFilter(ParametricFilter.Type.LowShelf, ToneParams.BASS_HZ, params.bassDb, ToneParams.SHELF_Q),
                ParametricFilter(ParametricFilter.Type.HighShelf, ToneParams.TREBLE_HZ, params.trebleDb, ToneParams.SHELF_Q),
            ) else emptyList()
        )
    }

    fun process(sample: Double, channel: Int): Double = chain.process(sample, channel)
}

/**
 * A look-ahead peak limiter: keeps every sample within [CEILING] by
 * turning the whole frame down smoothly just before a peak arrives
 * ([LOOKAHEAD_MS] ahead) and back up afterwards ([RELEASE_MS]), instead of
 * flattening the peak (the harsh crackle of hard clipping). The audio is
 * always delayed by the look-ahead, on or off, so switching it doesn't
 * jump. Frames go in and come out [lookaheadFrames] later via [process].
 */
class Limiter {
    private var channels = 0
    var lookaheadFrames = 0
        private set
    private var delay = DoubleArray(0)
    /** Each delayed frame's needed gain, for the window minimum. */
    private var needed = DoubleArray(0)
    private var position = 0
    private var gain = 1.0
    private var attack = 0.0
    private var release = 0.0

    /** Off = the audio is still delayed, but never turned down. */
    @Volatile
    var enabled = true

    fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        lookaheadFrames = (LOOKAHEAD_MS * sampleRate / 1000.0).roundToInt().coerceAtLeast(1)
        delay = DoubleArray(lookaheadFrames * channels)
        needed = DoubleArray(lookaheadFrames) { 1.0 }
        position = 0
        gain = 1.0
        // Falls to the new level within the look-ahead; recovers over the release.
        attack = 1 - exp(-1.0 / (lookaheadFrames / 4.0))
        release = 1 - exp(-1.0 / (RELEASE_MS * sampleRate / 1000.0))
    }

    fun reset() {
        delay.fill(0.0)
        needed.fill(1.0)
        position = 0
        gain = 1.0
    }

    /** Puts [frame] in and replaces it with the frame from [lookaheadFrames] ago, limited. */
    fun process(frame: DoubleArray) {
        var peak = 0.0
        for (c in 0 until channels) peak = maxOf(peak, abs(frame[c]))
        val base = position * channels
        // Swap the new frame in and the oldest one out.
        for (c in 0 until channels) {
            val old = delay[base + c]
            delay[base + c] = frame[c]
            frame[c] = old
        }
        needed[position] = if (enabled && peak > CEILING) CEILING / peak else 1.0
        position = if (position + 1 == lookaheadFrames) 0 else position + 1

        // The quietest gain any frame in the window needs, so the peak is
        // already turned down by the time it comes out.
        var target = 1.0
        for (g in needed) if (g < target) target = g
        gain += (target - gain) * (if (target < gain) attack else release)
        // Smoothing can lag a sudden peak by a hair; never let one through.
        var outPeak = 0.0
        for (c in 0 until channels) outPeak = maxOf(outPeak, abs(frame[c]))
        val applied = if (enabled && outPeak * gain > CEILING) CEILING / outPeak else gain
        for (c in 0 until channels) frame[c] *= applied
    }

    companion object {
        /** Just under full scale (-0.3 dBFS), so the DAC's own filters don't clip. */
        const val CEILING = 0.966
        const val LOOKAHEAD_MS = 3.0
        const val RELEASE_MS = 120.0
    }
}
