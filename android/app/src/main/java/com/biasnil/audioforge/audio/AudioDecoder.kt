package com.biasnil.audioforge.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Part of a file as mono floats. [durationMs] is the whole file's length;
 * [complete] = the samples reach the end of the file.
 */
class Decoded(val samples: FloatArray, val durationMs: Long, val complete: Boolean)

/**
 * Decodes audio with Android's own codecs (MediaExtractor + MediaCodec) to
 * mono floats at a chosen sample rate. Used by the key/BPM analysis
 * (11,025 Hz, the start of the file) and genre detection (16 kHz, a window
 * from the middle).
 */
object AudioDecoder {

    /**
     * Mono floats at [sampleRate], from [startMs] for at most [maxMs].
     * [lowPass] filters out what the lower rate can't hold before
     * down-sampling (genre detection; the key/BPM analysis doesn't need it).
     */
    suspend fun decodeMono(
        context: Context,
        uri: Uri,
        sampleRate: Int,
        startMs: Long = 0,
        maxMs: Long,
        lowPass: Boolean = false,
    ): Decoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decodeLoop(extractor, codec, format, durationMs, sampleRate, startMs, maxMs, lowPass)
            } finally {
                try {
                    codec.stop()
                } catch (e: IllegalStateException) {
                    // never started
                }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        durationMs: Long,
        sampleRate: Int,
        startMs: Long,
        maxMs: Long,
        lowPass: Boolean,
    ): Decoded {
        var inputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val remainingMs = if (durationMs > 0) (durationMs - startMs).coerceAtLeast(0) else Long.MAX_VALUE
        val limit = (minOf(remainingMs, maxMs) / 1000.0 * sampleRate).toLong().coerceAtMost(maxMs / 1000 * sampleRate.toLong())
        val out = FloatBuilder((limit + 16_384).toInt().coerceAtMost(8_000_000))
        val resampler = Resampler()
        var filter: LowPassFilter? = null
        val startUs = startMs * 1000
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var iterations = 0

        // One input frame (all channels averaged): low-pass if asked, then down-sample.
        fun push(sample: Float) {
            val active = filter
            resampler.push(if (active != null) active.process(sample) else sample, out)
        }

        while (!outputDone && out.size < limit) {
            if (++iterations % 64 == 0) currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    inputRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIndex >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        resampler.ratio = inputRate.toDouble() / sampleRate
                        if (lowPass && filter?.inputRate != inputRate) filter = LowPassFilter(inputRate, LOW_PASS_CUTOFF_HZ)
                        // After a seek the decoder starts at the sync point before startMs: skip up to there.
                        var skip = if (startUs > 0 && info.presentationTimeUs < startUs) {
                            ((startUs - info.presentationTimeUs) * inputRate / 1_000_000L).toInt()
                        } else 0
                        if (floatPcm) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                if (skip > 0) skip-- else push(sum / channels)
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += shorts.get() / 32768f }
                                if (skip > 0) skip-- else push(sum / channels)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        val samples = out.toArray(limit.toInt())
        val fullMs = if (durationMs > 0) durationMs else startMs + samples.size * 1000L / sampleRate
        return Decoded(samples, fullMs, fullMs <= startMs + maxMs)
    }

    /** Genre detection: YAMNet listens to 125-7500 Hz; this stops 8-14 kHz folding down into it at 16 kHz. */
    private const val LOW_PASS_CUTOFF_HZ = 7_200.0

    /** Down-samples by averaging the input over each output sample's span (a box filter, enough for beats and notes). */
    private class Resampler {
        var ratio = 1.0
        private var accumulated = 0.0
        private var count = 0.0
        private var position = 0.0

        fun push(sample: Float, out: FloatBuilder) {
            accumulated += sample
            count += 1
            position += 1
            if (position >= ratio) {
                out.add((accumulated / count).toFloat())
                accumulated = 0.0
                count = 0.0
                position -= ratio
            }
        }
    }

    private class FloatBuilder(capacity: Int) {
        private var data = FloatArray(maxOf(capacity, 1024))
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(max: Int): FloatArray = data.copyOf(minOf(size, max))
    }
}

/**
 * A windowed-sinc low-pass FIR (Hamming window), run one sample at a time.
 * Plain Kotlin, tested on the JVM.
 */
class LowPassFilter(val inputRate: Int, cutoffHz: Double, taps: Int = 47) {
    private val kernel = lowPassKernel(cutoffHz / inputRate, taps)
    private val history = FloatArray(taps)
    private var position = 0

    fun process(sample: Float): Float {
        history[position] = sample
        var sum = 0f
        var index = position
        for (k in kernel.indices) {
            sum += kernel[k] * history[index]
            index = if (index == 0) history.size - 1 else index - 1
        }
        position = if (position == history.size - 1) 0 else position + 1
        return sum
    }
}

/** The filter's coefficients for a cutoff given as a fraction of the sample rate (0..0.5); they sum to 1. */
fun lowPassKernel(cutoff: Double, taps: Int): FloatArray {
    require(taps % 2 == 1) { "taps must be odd" }
    val middle = taps / 2
    val raw = DoubleArray(taps) { i ->
        val n = i - middle
        val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
        val window = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))
        sinc * window
    }
    val sum = raw.sum()
    return FloatArray(taps) { (raw[it] / sum).toFloat() }
}
