package com.biasnil.audioforge.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Karaoke vocal removal, shared by both players' processors like
 * [EqualizerSettings]: 0 = off, 1 = as much as possible. (The key change
 * is done by the player itself, see PlaybackManager.)
 */
class KaraokeSettings {
    @Volatile
    var vocalRemoval = 0f
        private set

    fun update(vocalRemoval: Float) {
        this.vocalRemoval = vocalRemoval.coerceIn(0f, 1f)
    }
}

/**
 * Takes out what's in the middle of a stereo mix -- usually the lead
 * voice -- but only in the voice's range: the middle's bass (kick, bass
 * guitar) and air (cymbals) are kept, so the backing still sounds full.
 * Mid/side: mid = (L+R)/2 minus [amount] x its 150 Hz..7 kHz band, side
 * untouched. Needs two channels; mono sources pass through unchanged.
 * No Android/Media3 types, so it's unit testable.
 */
class KaraokeDsp(private val settings: KaraokeSettings) {
    private val low = Biquad()
    private val high = Biquad()
    private var amount = 0.0
    private var channels = 0

    /** The last frame's output; read after [process]. */
    var outLeft = 0.0
        private set
    var outRight = 0.0
        private set

    var isActive = false
        private set

    fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        low.lowPass(sampleRate, LOW_KEEP_HZ)
        high.highPass(sampleRate, minOf(HIGH_KEEP_HZ, sampleRate * 0.45))
        reset()
        refresh()
    }

    fun reset() {
        low.reset()
        high.reset()
    }

    fun refresh() {
        amount = settings.vocalRemoval.toDouble()
        isActive = channels >= 2 && amount > 0
    }

    fun process(left: Double, right: Double) {
        val mid = (left + right) * 0.5
        val side = (left - right) * 0.5
        val voiceBand = mid - low.process(mid) - high.process(mid)
        val keptMid = mid - amount * voiceBand
        outLeft = keptMid + side
        outRight = keptMid - side
    }

    /** One second-order (Butterworth) filter on one signal. */
    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun lowPass(sampleRate: Int, hz: Double) = set(sampleRate, hz, high = false)

        fun highPass(sampleRate: Int, hz: Double) = set(sampleRate, hz, high = true)

        private fun set(sampleRate: Int, hz: Double, high: Boolean) {
            val w0 = 2 * PI * hz / sampleRate
            val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha
            val edge = if (high) (1 + cosW0) / 2 else (1 - cosW0) / 2
            b0 = edge / a0
            b1 = (if (high) -(1 + cosW0) else 1 - cosW0) / a0
            b2 = edge / a0
            a1 = -2 * cosW0 / a0
            a2 = (1 - alpha) / a0
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private companion object {
        const val LOW_KEEP_HZ = 150.0
        const val HIGH_KEEP_HZ = 7000.0
        const val BUTTERWORTH_Q = 0.7071
    }
}

/** Semitones -> the player's pitch factor (12 semitones = an octave = x2). */
fun pitchFactor(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()
