package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.ParametricFilter
import kotlin.math.abs
import kotlin.math.exp

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
 * A peak limiter: keeps every sample within [CEILING] by turning the whole
 * frame down the moment a peak would go over, then back up smoothly
 * ([RELEASE_MS]) -- instead of flattening the peak (the harsh crackle of
 * hard clipping). No look-ahead, so it adds no delay: a look-ahead's
 * held-back audio had to be flushed whenever the player changed speed or
 * key, and that flush silenced the output.
 */
class Limiter {
    private var channels = 0
    private var gain = 1.0
    private var release = 0.0

    /** Off = never turns anything down (what's over full scale is clipped later). */
    @Volatile
    var enabled = true

    fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        gain = 1.0
        release = 1 - exp(-1.0 / (RELEASE_MS * sampleRate / 1000.0))
    }

    fun reset() {
        gain = 1.0
    }

    /** Limits [frame] in place. */
    fun process(frame: DoubleArray) {
        if (!enabled) {
            gain = 1.0
            return
        }
        var peak = 0.0
        for (c in 0 until channels) peak = maxOf(peak, abs(frame[c]))
        val needed = if (peak > CEILING) CEILING / peak else 1.0
        gain = if (needed < gain) needed else gain + (needed - gain) * release
        if (gain < 1.0) for (c in 0 until channels) frame[c] *= gain
    }

    companion object {
        /** Just under full scale (-0.3 dBFS), so the DAC's own filters don't clip. */
        const val CEILING = 0.966
        const val RELEASE_MS = 120.0
    }
}
