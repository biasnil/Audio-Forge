package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.HeadphoneProfile
import com.biasnil.audioforge.data.ParametricFilter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The headphone correction in use (null = off), shared by both players'
 * audio processors like [EqualizerSettings]. Written on the main thread,
 * read on the audio threads.
 */
class CorrectionSettings {
    @Volatile
    var profile: HeadphoneProfile? = null
        private set

    fun update(profile: HeadphoneProfile?) {
        this.profile = profile
    }
}

/**
 * An AutoEq profile as a chain of biquads (RBJ audio-EQ-cookbook peaking
 * and shelving filters, with the shelves' Q as AutoEq means it), after its
 * preamp. Separate filter state per channel. No Android/Media3 types, so
 * it's unit testable; [AudioEffectsProcessor] feeds it samples.
 */
class CorrectionDsp(private val settings: CorrectionSettings) {

    private var sampleRate = 0
    private var channels = 0
    private var applied: HeadphoneProfile? = null
    private var appliedOnce = false

    private var count = 0
    private var b0 = DoubleArray(0)
    private var b1 = DoubleArray(0)
    private var b2 = DoubleArray(0)
    private var a1 = DoubleArray(0)
    private var a2 = DoubleArray(0)
    /** Per filter, per channel: x[n-1], x[n-2], y[n-1], y[n-2]. */
    private var state = DoubleArray(0)
    private var preamp = 1.0

    /** True when a profile is chosen -- the processor skips the correction otherwise. */
    var isActive = false
        private set

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        appliedOnce = false
        refresh()
    }

    /** Clears filter history (after a seek or a new track), not the settings. */
    fun reset() {
        state.fill(0.0)
    }

    /** Picks up a newly chosen profile; cheap when nothing changed. Call once per buffer. */
    fun refresh() {
        val profile = settings.profile
        if ((appliedOnce && profile === applied) || sampleRate <= 0) return
        applied = profile
        appliedOnce = true
        // Filters at or above Nyquist can't be built (and are inaudible there anyway).
        val filters = profile?.filters.orEmpty().filter { it.frequencyHz < sampleRate / 2.0 }
        count = filters.size
        b0 = DoubleArray(count)
        b1 = DoubleArray(count)
        b2 = DoubleArray(count)
        a1 = DoubleArray(count)
        a2 = DoubleArray(count)
        state = DoubleArray(count * channels * 4)
        filters.forEachIndexed { i, filter -> design(i, filter) }
        preamp = 10.0.pow((profile?.preampDb ?: 0.0) / 20.0)
        isActive = profile != null
    }

    /** One sample of one channel through the preamp and every filter. */
    fun process(sample: Double, channel: Int): Double {
        var x = sample * preamp
        for (f in 0 until count) {
            val i = (f * channels + channel) * 4
            val y = b0[f] * x + b1[f] * state[i] + b2[f] * state[i + 1] - a1[f] * state[i + 2] - a2[f] * state[i + 3]
            state[i + 1] = state[i]
            state[i] = x
            state[i + 3] = state[i + 2]
            state[i + 2] = y
            x = y
        }
        return x
    }

    private fun design(i: Int, filter: ParametricFilter) {
        val a = 10.0.pow(filter.gainDb / 40.0)
        val w0 = 2.0 * PI * filter.frequencyHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * filter.q)
        val n0: Double
        val n1: Double
        val n2: Double
        val d0: Double
        val d1: Double
        val d2: Double
        when (filter.type) {
            ParametricFilter.Type.Peaking -> {
                n0 = 1 + alpha * a
                n1 = -2 * cosW0
                n2 = 1 - alpha * a
                d0 = 1 + alpha / a
                d1 = -2 * cosW0
                d2 = 1 - alpha / a
            }
            ParametricFilter.Type.LowShelf -> {
                val s = 2 * sqrt(a) * alpha
                n0 = a * ((a + 1) - (a - 1) * cosW0 + s)
                n1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
                n2 = a * ((a + 1) - (a - 1) * cosW0 - s)
                d0 = (a + 1) + (a - 1) * cosW0 + s
                d1 = -2 * ((a - 1) + (a + 1) * cosW0)
                d2 = (a + 1) + (a - 1) * cosW0 - s
            }
            ParametricFilter.Type.HighShelf -> {
                val s = 2 * sqrt(a) * alpha
                n0 = a * ((a + 1) + (a - 1) * cosW0 + s)
                n1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
                n2 = a * ((a + 1) + (a - 1) * cosW0 - s)
                d0 = (a + 1) - (a - 1) * cosW0 + s
                d1 = 2 * ((a - 1) - (a + 1) * cosW0)
                d2 = (a + 1) - (a - 1) * cosW0 - s
            }
        }
        b0[i] = n0 / d0
        b1[i] = n1 / d0
        b2[i] = n2 / d0
        a1[i] = d1 / d0
        a2[i] = d2 / d0
    }
}
