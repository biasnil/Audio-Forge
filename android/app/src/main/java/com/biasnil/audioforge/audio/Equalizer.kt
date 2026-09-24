package com.biasnil.audioforge.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The equalizer's current settings, shared by both players' audio
 * processors (so a crossfade doesn't lose the EQ halfway through, same as
 * the desktop). Written on the main thread, read on the audio threads.
 */
class EqualizerSettings {
    @Volatile
    var enabled = false
        private set

    @Volatile
    var gainsDb: FloatArray = FloatArray(BAND_COUNT)
        private set

    /** Bumped on every change so processors know to recompute their filters. */
    @Volatile
    var version = 0
        private set

    fun update(enabled: Boolean, gainsDb: List<Float>) {
        val gains = FloatArray(BAND_COUNT) { gainsDb.getOrElse(it) { 0f } }
        if (enabled == this.enabled && gains.contentEquals(this.gainsDb)) return
        this.gainsDb = gains
        this.enabled = enabled
        version++
    }

    companion object {
        const val BAND_COUNT = 10

        /** The desktop's standard 10-band graphic EQ spacing, roughly an octave apart. */
        val FREQUENCIES_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        /** Same as the desktop's ma_peak_node bands. */
        const val Q = 1.0

        const val MIN_GAIN_DB = -15f
        const val MAX_GAIN_DB = 15f
    }
}

/**
 * The 10 peaking filters in series (RBJ audio-EQ-cookbook biquads), with
 * separate filter state per channel. No Android/Media3 types, so it's unit
 * testable; [AudioEffectsProcessor] feeds it samples.
 */
class EqualizerDsp(private val settings: EqualizerSettings) {

    private val bands = EqualizerSettings.BAND_COUNT
    private val b0 = DoubleArray(bands)
    private val b1 = DoubleArray(bands)
    private val b2 = DoubleArray(bands)
    private val a1 = DoubleArray(bands)
    private val a2 = DoubleArray(bands)
    private val bandActive = BooleanArray(bands)

    private var sampleRate = 0
    private var channels = 0
    /** Per band, per channel: x[n-1], x[n-2], y[n-1], y[n-2]. */
    private var state = DoubleArray(0)
    private var appliedVersion = -1

    /** True if any band currently does something -- the processor skips the EQ entirely otherwise. */
    var isActive = false
        private set

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        state = DoubleArray(bands * channels * 4)
        appliedVersion = -1
        refresh()
    }

    /** Clears filter history (after a seek or a new track), not the settings. */
    fun reset() {
        state.fill(0.0)
    }

    /** Picks up new settings; cheap when nothing changed. Call once per buffer. */
    fun refresh() {
        val version = settings.version
        if (version == appliedVersion || sampleRate <= 0) return
        appliedVersion = version

        val gains = settings.gainsDb
        var anyActive = false
        for (band in 0 until bands) {
            val frequency = EqualizerSettings.FREQUENCIES_HZ[band]
            val gainDb = gains[band].toDouble()
            // A 0 dB band is a no-op; skip it. So is one at or above Nyquist
            // (16 kHz at a 32 kHz sample rate or lower).
            if (gainDb == 0.0 || frequency >= sampleRate / 2.0) {
                bandActive[band] = false
                continue
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * EqualizerSettings.Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[band] = (1.0 + alpha * a) / a0
            b1[band] = (-2.0 * cosW0) / a0
            b2[band] = (1.0 - alpha * a) / a0
            a1[band] = (-2.0 * cosW0) / a0
            a2[band] = (1.0 - alpha / a) / a0
            bandActive[band] = true
            anyActive = true
        }
        isActive = settings.enabled && anyActive
    }

    /** One sample of one channel through every active band. */
    fun process(sample: Double, channel: Int): Double {
        var x = sample
        for (band in 0 until bands) {
            if (!bandActive[band]) continue
            val i = (band * channels + channel) * 4
            val y = b0[band] * x + b1[band] * state[i] + b2[band] * state[i + 1] -
                a1[band] * state[i + 2] - a2[band] * state[i + 3]
            state[i + 1] = state[i]
            state[i] = x
            state[i + 3] = state[i + 2]
            state[i + 2] = y
            x = y
        }
        return x
    }
}

/** The desktop's preset curves (dB per band, 31 Hz ... 16 kHz). */
val EqualizerPresets: List<Pair<String, List<Float>>> = listOf(
    "Rock" to listOf(5f, 4f, 3f, 0f, -2f, -1f, 0f, 2f, 3f, 4f),
    "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, -1f, -1f),
    "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
    "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -2f, -2f, 0f, 2f, 3f),
    "Dance" to listOf(6f, 5f, 2f, 0f, 0f, -3f, -3f, 0f, 3f, 4f),
    "Hip-Hop" to listOf(6f, 5f, 3f, 1f, -1f, -1f, 0f, 1f, 2f, 3f),
    "Blues" to listOf(3f, 2f, 0f, -1f, 0f, 1f, 2f, 2f, 1f, 1f),
    "Vocal Boost" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
)

/** dB -> linear gain multiplier, 10^(dB/20) -- the desktop's ReplayGain/post-gain conversion. */
fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

/**
 * A ReplayGain tag value ("-6.54 dB", "+1.2", "−3 dB") in dB, or null if it
 * doesn't parse.
 */
fun parseReplayGainDb(value: String?): Float? =
    value?.replace("dB", "", ignoreCase = true)
        ?.replace('−', '-') // Unicode minus sign
        ?.trim()
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
