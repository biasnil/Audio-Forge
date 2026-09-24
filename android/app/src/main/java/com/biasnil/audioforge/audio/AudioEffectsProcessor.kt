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
 * Runs inside one player's audio pipeline: the 10-band EQ, then a gain --
 * volume x ReplayGain x EQ post-gain, the desktop's baseVolumeFor() -- which
 * can exceed 1 for the volume boost. Output is hard-clipped to full scale,
 * same as the desktop (no limiter yet).
 *
 * Gain changes are ramped across a buffer so moving the volume slider
 * doesn't click.
 */
@OptIn(UnstableApi::class)
class AudioEffectsProcessor(equalizer: EqualizerSettings) : BaseAudioProcessor() {

    /** Set from the main thread; picked up on the next buffer. */
    @Volatile
    var gain = 1f

    private val eq = EqualizerDsp(equalizer)
    private var appliedGain = 1f
    private var encoding = C.ENCODING_INVALID
    private var channels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val format = inputAudioFormat.encoding
        if (format != C.ENCODING_PCM_16BIT && format != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = format
        channels = inputAudioFormat.channelCount
        eq.configure(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat // same format out
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // Decoded PCM is in the platform's byte order.
        inputBuffer.order(ByteOrder.nativeOrder())
        eq.refresh()

        val bytesPerSample = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val frames = size / (bytesPerSample * channels)
        val output = replaceOutputBuffer(frames * bytesPerSample * channels)
        val startGain = appliedGain
        val endGain = gain
        val useEq = eq.isActive

        for (frame in 0 until frames) {
            val frameGain = if (frames > 1) startGain + (endGain - startGain) * frame / (frames - 1) else endGain
            for (channel in 0 until channels) {
                var sample = if (bytesPerSample == 2) {
                    inputBuffer.getShort() / 32768.0
                } else {
                    inputBuffer.getFloat().toDouble()
                }
                if (useEq) sample = eq.process(sample, channel)
                val out = (sample * frameGain).coerceIn(-1.0, 1.0)
                if (bytesPerSample == 2) {
                    output.putShort((out * 32767.0).roundToInt().toShort())
                } else {
                    output.putFloat(out.toFloat())
                }
            }
        }
        appliedGain = endGain
        inputBuffer.position(inputBuffer.limit()) // any partial trailing frame is dropped
        output.flip()
    }

    override fun onFlush() {
        eq.reset() // seek / new track: don't ring with the old audio's filter history
        appliedGain = gain
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
        appliedGain = gain
    }
}
