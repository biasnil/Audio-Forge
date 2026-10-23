package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.ReverbPreset
import com.biasnil.audioforge.data.SurroundPreset
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/** One consistent set of effect values; replaced as a whole so the audio thread never sees half an update. */
data class SpatialParams(
    val surround: SurroundPreset = SurroundPreset.Off,
    /** 0..1: how much of the virtual room is mixed in. */
    val surroundAmount: Float = 0.5f,
    /** 0 = mono, 1 = unchanged, 2 = twice as wide. */
    val width: Float = 1f,
    val reverb: ReverbPreset = ReverbPreset.Off,
    /** 0..1: dry/wet balance of the reverb. */
    val reverbMix: Float = 0.3f,
) {
    /** True when the output would equal the input, so the processor can skip the effects. */
    val isNeutral: Boolean
        get() = (surround == SurroundPreset.Off || surroundAmount <= 0f) &&
            width == 1f &&
            (reverb == ReverbPreset.Off || reverbMix <= 0f)
}

/**
 * The surround / width / reverb settings, shared by both players' audio
 * processors like [EqualizerSettings]. Written on the main thread, read on
 * the audio threads.
 */
class SpatialSettings {
    @Volatile
    var params = SpatialParams()
        private set

    fun update(params: SpatialParams) {
        this.params = params
    }
}

/**
 * Stereo width, 3D Surround and reverb on one stereo frame at a time, in
 * that order. Surround is a small headphone virtualizer: each ear also hears
 * the other side a fraction of a millisecond later with the head's shadow
 * (a low-pass), then early reflections off virtual walls, then a diffuse
 * tail. The tails are Freeverb (Jezar at Dreampoint, public domain): 8
 * damped comb filters and 4 all-passes per side.
 *
 * Needs at least two channels (only the first two are touched); mono
 * sources pass through unchanged. No Android/Media3 types, so it's unit
 * testable; [AudioEffectsProcessor] feeds it samples.
 */
class SpatialDsp(private val settings: SpatialSettings) {

    private var sampleRate = 0
    private var channels = 0
    private var applied: SpatialParams? = null

    private var width = 1.0
    private var surroundAmount = 0.0
    private var reverbMix = 0.0
    private var surround: SurroundPreset = SurroundPreset.Off

    // Surround: crosstalk to the opposite ear + early reflections, from per-side delay lines.
    private var delayL = DelayLine(1)
    private var delayR = DelayLine(1)
    private var crosstalkSamples = 0
    /** One-pole low-pass for the head shadow, per ear. */
    private var shadowCoefficient = 0.0
    private var shadowL = 0.0
    private var shadowR = 0.0
    private var reflections: Array<Reflection> = emptyArray()
    private val surroundTail = Freeverb()
    private var surroundTailLevel = 0.0

    private val reverb = Freeverb()
    private var reverbOn = false

    /** The last frame's output; read after [process]. */
    var outLeft = 0.0
        private set
    var outRight = 0.0
        private set

    /** True if any effect currently does something -- the processor skips this entirely otherwise. */
    var isActive = false
        private set

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        val maxDelay = (MAX_DELAY_MS * sampleRate / 1000.0).roundToInt() + 2
        delayL = DelayLine(maxDelay)
        delayR = DelayLine(maxDelay)
        surroundTail.configure(sampleRate)
        reverb.configure(sampleRate)
        crosstalkSamples = (CROSSTALK_DELAY_MS * sampleRate / 1000.0).roundToInt().coerceAtLeast(1)
        shadowCoefficient = exp(-2.0 * PI * HEAD_SHADOW_HZ / sampleRate)
        applied = null
        reset()
        refresh()
    }

    /** Clears delay lines and tails (after a seek or a new track), not the settings. */
    fun reset() {
        delayL.clear()
        delayR.clear()
        shadowL = 0.0
        shadowR = 0.0
        surroundTail.clear()
        reverb.clear()
    }

    /** Picks up new settings; cheap when nothing changed. Call once per buffer. */
    fun refresh() {
        val params = settings.params
        if (params == applied || sampleRate <= 0) return
        val old = applied
        applied = params

        width = params.width.toDouble().coerceIn(0.0, 2.0)
        surround = if (params.surroundAmount > 0f) params.surround else SurroundPreset.Off
        surroundAmount = params.surroundAmount.toDouble().coerceIn(0.0, 1.0)
        if (surround != SurroundPreset.Off) {
            val room = SURROUND_ROOMS.getValue(surround)
            reflections = room.reflections.map {
                Reflection(
                    delaySamples = (it.delayMs * sampleRate / 1000.0).roundToInt().coerceAtLeast(1),
                    gain = it.gain,
                    fromSameSide = it.fromSameSide,
                    toLeft = it.toLeft,
                )
            }.toTypedArray()
            surroundTail.set(room.tail)
            surroundTailLevel = room.tailLevel
            // Changing rooms with the old tail still ringing sounds like a glitch.
            if (old?.surround != surround) surroundTail.clear()
        }
        reverbOn = params.reverb != ReverbPreset.Off && params.reverbMix > 0f
        reverbMix = params.reverbMix.toDouble().coerceIn(0.0, 1.0)
        if (reverbOn) {
            reverb.set(REVERB_ROOMS.getValue(params.reverb))
            if (old?.reverb != params.reverb) reverb.clear()
        }
        isActive = channels >= 2 && !params.isNeutral
    }

    /** One stereo frame through every active effect; the result is in [outLeft] / [outRight]. */
    fun process(left: Double, right: Double) {
        var l = left
        var r = right

        if (width != 1.0) {
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5 * width
            l = mid + side
            r = mid - side
        }

        if (surround != SurroundPreset.Off) {
            delayL.write(l)
            delayR.write(r)
            // Head shadow: the far ear hears the other side later and duller.
            shadowL += (1 - shadowCoefficient) * (delayR.read(crosstalkSamples) - shadowL)
            shadowR += (1 - shadowCoefficient) * (delayL.read(crosstalkSamples) - shadowR)
            var wetL = shadowL * CROSSTALK_GAIN
            var wetR = shadowR * CROSSTALK_GAIN
            for (reflection in reflections) {
                val source = if (reflection.fromSameSide == reflection.toLeft) delayL else delayR
                val value = source.read(reflection.delaySamples) * reflection.gain
                if (reflection.toLeft) wetL += value else wetR += value
            }
            surroundTail.process(l, r)
            wetL += surroundTail.outLeft * surroundTailLevel
            wetR += surroundTail.outRight * surroundTailLevel
            // The dry signal steps back a little so the room doesn't just add volume.
            val dry = 1.0 - SURROUND_DRY_DUCK * surroundAmount
            l = l * dry + wetL * surroundAmount
            r = r * dry + wetR * surroundAmount
        }

        if (reverbOn) {
            reverb.process(l, r)
            val dry = 1.0 - REVERB_DRY_DUCK * reverbMix
            l = l * dry + reverb.outLeft * reverbMix
            r = r * dry + reverb.outRight * reverbMix
        }

        outLeft = l
        outRight = r
    }

    private class Reflection(val delaySamples: Int, val gain: Double, val fromSameSide: Boolean, val toLeft: Boolean)

    companion object {
        /** Longest reflection (Stadium) plus headroom. */
        private const val MAX_DELAY_MS = 200.0
        /** About the extra distance sound travels around a head to the far ear. */
        private const val CROSSTALK_DELAY_MS = 0.27
        private const val CROSSTALK_GAIN = 0.45
        private const val HEAD_SHADOW_HZ = 1_800.0
        private const val SURROUND_DRY_DUCK = 0.3
        private const val REVERB_DRY_DUCK = 0.5
    }
}

/** A delay tap in a virtual room: [fromSameSide] = off the wall on the ear's own side. */
internal data class ReflectionSpec(val delayMs: Double, val gain: Double, val fromSameSide: Boolean, val toLeft: Boolean)

internal class SurroundRoom(val reflections: List<ReflectionSpec>, val tail: FreeverbParams, val tailLevel: Double)

/** Pairs of reflections, mirrored left/right, with each next one later and quieter. */
private fun reflectionsAt(vararg pairs: Pair<Double, Double>): List<ReflectionSpec> =
    pairs.flatMapIndexed { index, (delayMs, gain) ->
        val sameSide = index % 2 == 0
        listOf(
            ReflectionSpec(delayMs, gain, sameSide, toLeft = true),
            // The right ear's copy is offset slightly so the two sides aren't identical.
            ReflectionSpec(delayMs * 1.07, gain, sameSide, toLeft = false),
        )
    }

internal val SURROUND_ROOMS: Map<SurroundPreset, SurroundRoom> = mapOf(
    SurroundPreset.Room to SurroundRoom(
        reflectionsAt(7.0 to 0.42, 11.0 to 0.34, 17.0 to 0.26, 23.0 to 0.2, 29.0 to 0.15),
        FreeverbParams(roomSize = 0.5, damping = 0.6, width = 1.0, predelayMs = 8.0),
        tailLevel = 0.5,
    ),
    SurroundPreset.Hall to SurroundRoom(
        reflectionsAt(13.0 to 0.38, 21.0 to 0.32, 31.0 to 0.26, 43.0 to 0.2, 57.0 to 0.15, 71.0 to 0.11),
        FreeverbParams(roomSize = 0.82, damping = 0.4, width = 1.0, predelayMs = 20.0),
        tailLevel = 0.75,
    ),
    SurroundPreset.Stadium to SurroundRoom(
        reflectionsAt(31.0 to 0.34, 53.0 to 0.28, 79.0 to 0.22, 107.0 to 0.17, 137.0 to 0.13, 171.0 to 0.1),
        FreeverbParams(roomSize = 0.93, damping = 0.3, width = 1.0, predelayMs = 40.0),
        tailLevel = 0.9,
    ),
)

internal val REVERB_ROOMS: Map<ReverbPreset, FreeverbParams> = mapOf(
    ReverbPreset.SmallRoom to FreeverbParams(roomSize = 0.45, damping = 0.65, width = 0.8, predelayMs = 4.0),
    ReverbPreset.LargeRoom to FreeverbParams(roomSize = 0.7, damping = 0.5, width = 1.0, predelayMs = 12.0),
    ReverbPreset.Hall to FreeverbParams(roomSize = 0.88, damping = 0.35, width = 1.0, predelayMs = 25.0),
    // A metal plate: bright (little damping), dense from the start (no pre-delay).
    ReverbPreset.Plate to FreeverbParams(roomSize = 0.8, damping = 0.1, width = 1.0, predelayMs = 0.0),
)

/** Freeverb's knobs, 0..1 except [predelayMs]. */
internal data class FreeverbParams(val roomSize: Double, val damping: Double, val width: Double, val predelayMs: Double)

/** A circular buffer; [read] (1 = the sample just written) must be below its size. */
private class DelayLine(size: Int) {
    private val buffer = DoubleArray(size)
    private var index = 0

    fun write(sample: Double) {
        index = if (index + 1 >= buffer.size) 0 else index + 1
        buffer[index] = sample
    }

    fun read(delay: Int): Double {
        var i = index - delay + 1
        if (i < 0) i += buffer.size
        return buffer[i]
    }

    fun clear() {
        buffer.fill(0.0)
    }
}

/**
 * Jezar's Freeverb (public domain): stereo in, a wet-only stereo tail out
 * in [outLeft] / [outRight], at roughly the input's loudness.
 */
private class Freeverb {
    private var left: Array<Comb> = emptyArray()
    private var right: Array<Comb> = emptyArray()
    private var allpassLeft: Array<Allpass> = emptyArray()
    private var allpassRight: Array<Allpass> = emptyArray()
    private var predelay = DelayLine(1)
    private var sampleRate = 44_100
    private var predelaySamples = 0
    private var feedback = 0.0
    private var damping = 0.0
    private var wet1 = 1.0
    private var wet2 = 0.0

    var outLeft = 0.0
        private set
    var outRight = 0.0
        private set

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate
        // Freeverb's tunings are in samples at 44.1 kHz.
        val scale = sampleRate / 44_100.0
        fun size(samples: Int) = (samples * scale).roundToInt().coerceAtLeast(1)
        left = Array(COMBS.size) { Comb(size(COMBS[it])) }
        right = Array(COMBS.size) { Comb(size(COMBS[it] + STEREO_SPREAD)) }
        allpassLeft = Array(ALLPASSES.size) { Allpass(size(ALLPASSES[it])) }
        allpassRight = Array(ALLPASSES.size) { Allpass(size(ALLPASSES[it] + STEREO_SPREAD)) }
        predelay = DelayLine((MAX_PREDELAY_MS * sampleRate / 1000.0).roundToInt() + 2)
    }

    fun set(params: FreeverbParams) {
        feedback = params.roomSize.coerceIn(0.0, 1.0) * SCALE_ROOM + OFFSET_ROOM
        damping = params.damping.coerceIn(0.0, 1.0) * SCALE_DAMP
        val width = params.width.coerceIn(0.0, 1.0)
        wet1 = WET_SCALE * (width / 2 + 0.5)
        wet2 = WET_SCALE * ((1 - width) / 2)
        predelaySamples = (params.predelayMs.coerceIn(0.0, MAX_PREDELAY_MS) * sampleRate / 1000.0).roundToInt()
    }

    fun clear() {
        left.forEach { it.clear() }
        right.forEach { it.clear() }
        allpassLeft.forEach { it.clear() }
        allpassRight.forEach { it.clear() }
        predelay.clear()
    }

    fun process(inLeft: Double, inRight: Double) {
        var input = (inLeft + inRight) * FIXED_GAIN
        if (predelaySamples > 0) {
            predelay.write(input)
            input = predelay.read(predelaySamples)
        }
        var l = 0.0
        var r = 0.0
        for (comb in left) l += comb.process(input, feedback, damping)
        for (comb in right) r += comb.process(input, feedback, damping)
        for (allpass in allpassLeft) l = allpass.process(l)
        for (allpass in allpassRight) r = allpass.process(r)
        outLeft = l * wet1 + r * wet2
        outRight = r * wet1 + l * wet2
    }

    private class Comb(size: Int) {
        private val buffer = DoubleArray(size)
        private var index = 0
        private var filterStore = 0.0

        fun process(input: Double, feedback: Double, damping: Double): Double {
            val output = buffer[index]
            filterStore = output * (1 - damping) + filterStore * damping
            // Flush values too small to hear, which some CPUs handle very slowly.
            if (filterStore < 1e-18 && filterStore > -1e-18) filterStore = 0.0
            buffer[index] = input + filterStore * feedback
            if (++index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0.0)
            filterStore = 0.0
        }
    }

    private class Allpass(size: Int) {
        private val buffer = DoubleArray(size)
        private var index = 0

        fun process(input: Double): Double {
            val stored = buffer[index]
            buffer[index] = input + stored * 0.5
            if (++index >= buffer.size) index = 0
            return stored - input
        }

        fun clear() {
            buffer.fill(0.0)
        }
    }

    companion object {
        private val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASSES = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23
        private const val FIXED_GAIN = 0.015
        private const val SCALE_ROOM = 0.28
        private const val OFFSET_ROOM = 0.7
        private const val SCALE_DAMP = 0.4
        /** Freeverb's default wet level (its 1/3 x a scale of 3). */
        private const val WET_SCALE = 1.0
        private const val MAX_PREDELAY_MS = 50.0
    }
}
