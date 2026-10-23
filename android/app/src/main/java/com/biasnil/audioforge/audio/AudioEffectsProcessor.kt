package com.biasnil.audioforge.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Runs inside one player's audio pipeline: headphone correction
 * ([CorrectionDsp]), the 10-band EQ, bass boost / loudness ([ToneDsp]),
 * karaoke vocal removal ([KaraokeDsp]), stereo width / 3D Surround /
 * reverb ([SpatialDsp]), mono / balance ([applyChannels]), then a gain --
 * volume x ReplayGain x EQ post-gain, the desktop's baseVolumeFor() --
 * which can exceed 1 for the volume boost, then the [Limiter]. Anything
 * still over full scale (the limiter switched off) is hard-clipped, same
 * as the desktop.
 *
 * Gain changes are ramped across a buffer so moving the volume slider
 * doesn't click.
 */
@OptIn(UnstableApi::class)
class AudioEffectsProcessor(
    equalizer: EqualizerSettings,
    spatial: SpatialSettings,
    correction: CorrectionSettings,
    tone: ToneSettings,
    karaoke: KaraokeSettings,
    private val channelSettings: ChannelSettings,
) : BaseAudioProcessor() {

    /** Set from the main thread; picked up on the next buffer. */
    @Volatile
    var gain = 1f

    /** Set from the main thread; see [Limiter.enabled]. */
    var limiterEnabled: Boolean
        get() = limiter.enabled
        set(value) {
            limiter.enabled = value
        }

    /** Where this player's output goes for the visualizer, while it's the one being heard. */
    @Volatile
    var visualizer: VisualizerTap? = null
    private var sampleRate = 44_100

    private val headphones = CorrectionDsp(correction)
    private val eq = EqualizerDsp(equalizer)
    private val toneDsp = ToneDsp(tone)
    private val vocals = KaraokeDsp(karaoke)
    private val effects = SpatialDsp(spatial)
    private val limiter = Limiter()
    private var appliedGain = 1f
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    /** One frame's samples, between reading and writing. */
    private var frameSamples = DoubleArray(0)

    private val bytesPerSample: Int get() = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val format = inputAudioFormat.encoding
        if (format != C.ENCODING_PCM_16BIT && format != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = format
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        headphones.configure(inputAudioFormat.sampleRate, channels)
        eq.configure(inputAudioFormat.sampleRate, channels)
        toneDsp.configure(inputAudioFormat.sampleRate, channels)
        vocals.configure(inputAudioFormat.sampleRate, channels)
        effects.configure(inputAudioFormat.sampleRate, channels)
        limiter.configure(inputAudioFormat.sampleRate, channels)
        frameSamples = DoubleArray(channels)
        return inputAudioFormat // same format out
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // Decoded PCM is in the platform's byte order.
        inputBuffer.order(ByteOrder.nativeOrder())
        headphones.refresh()
        eq.refresh()
        toneDsp.refresh()
        vocals.refresh()
        effects.refresh()

        val bytesPerSample = bytesPerSample
        val frames = size / (bytesPerSample * channels)
        val output = replaceOutputBuffer(frames * bytesPerSample * channels)
        val startGain = appliedGain
        val endGain = gain
        val useHeadphones = headphones.isActive
        val useEq = eq.isActive
        val useTone = toneDsp.isActive
        val useVocals = vocals.isActive
        val useEffects = effects.isActive
        val mono = channelSettings.mono
        val balance = channelSettings.balance
        val useChannels = mono || balance != 0f
        val samples = frameSamples
        val tap = visualizer?.takeIf { it.enabled }
        tap?.sampleRate = sampleRate

        for (frame in 0 until frames) {
            val frameGain = if (frames > 1) startGain + (endGain - startGain) * frame / (frames - 1) else endGain
            for (channel in 0 until channels) {
                var sample = if (bytesPerSample == 2) {
                    inputBuffer.getShort() / 32768.0
                } else {
                    inputBuffer.getFloat().toDouble()
                }
                if (useHeadphones) sample = headphones.process(sample, channel)
                if (useEq) sample = eq.process(sample, channel)
                if (useTone) sample = toneDsp.process(sample, channel)
                samples[channel] = sample
            }
            if (useVocals) { // only when there are 2+ channels
                vocals.process(samples[0], samples[1])
                samples[0] = vocals.outLeft
                samples[1] = vocals.outRight
            }
            if (useEffects) { // only when there are 2+ channels
                effects.process(samples[0], samples[1])
                samples[0] = effects.outLeft
                samples[1] = effects.outRight
            }
            if (useChannels) applyChannels(samples, mono, balance)
            for (channel in 0 until channels) samples[channel] *= frameGain
            limiter.process(samples)
            writeFrame(output, samples, tap)
        }
        appliedGain = endGain
        inputBuffer.position(inputBuffer.limit()) // any partial trailing frame is dropped
        output.flip()
    }

    /** Clips what's still over full scale, writes the frame, and feeds the visualizer. */
    private fun writeFrame(output: ByteBuffer, samples: DoubleArray, tap: VisualizerTap?) {
        var mono = 0.0
        for (channel in 0 until channels) {
            val out = samples[channel].coerceIn(-1.0, 1.0)
            if (channel < 2) mono += out
            if (bytesPerSample == 2) {
                output.putShort((out * 32767.0).roundToInt().toShort())
            } else {
                output.putFloat(out.toFloat())
            }
        }
        tap?.push(mono.toFloat())
    }

    override fun onFlush() {
        headphones.reset()
        eq.reset() // seek / new track: don't ring with the old audio's filter history
        toneDsp.reset()
        vocals.reset()
        effects.reset()
        limiter.reset()
        appliedGain = gain
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
        appliedGain = gain
    }
}
