package com.biasnil.audioforge.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.biasnil.audioforge.data.SongAnalysis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/**
 * Finds a song's tempo (BPM and beat grid), key, silence at the start/end
 * and where its ending fades out -- on the phone, no network. Decodes with
 * Android's own codecs, then runs [AnalysisMath] (a port of the iOS app's
 * AudioAnalyzer).
 */
object AudioAnalyzer {
    private const val TAG = "AudioAnalyzer"

    suspend fun analyze(context: Context, uri: Uri): SongAnalysis? {
        val decoded = try {
            decodeMono(context, uri)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Couldn't decode $uri", e)
            null
        } ?: return null
        currentCoroutineContext().ensureActive()
        return AnalysisMath.analyze(decoded.samples, decoded.durationMs, decoded.complete)
    }

    private class Decoded(val samples: FloatArray, val durationMs: Long, val complete: Boolean)

    /** The file as mono floats at [AnalysisMath.SAMPLE_RATE], at most [AnalysisMath.MAX_ANALYZED_SECONDS] of it. */
    private suspend fun decodeMono(context: Context, uri: Uri): Decoded? {
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

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decodeLoop(extractor, codec, format, durationMs)
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

    private suspend fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, durationMs: Long): Decoded {
        var inputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val limit = (minOf(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE, AnalysisMath.MAX_ANALYZED_SECONDS * 1000L) / 1000.0 *
            AnalysisMath.SAMPLE_RATE).toLong().coerceAtMost(AnalysisMath.MAX_ANALYZED_SECONDS * AnalysisMath.SAMPLE_RATE.toLong())
        val out = FloatBuilder((limit + 16_384).toInt().coerceAtMost(8_000_000))
        val resampler = Resampler()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var iterations = 0

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
                        resampler.ratio = inputRate.toDouble() / AnalysisMath.SAMPLE_RATE
                        if (floatPcm) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                resampler.push(sum / channels, out)
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += shorts.get() / 32768f }
                                resampler.push(sum / channels, out)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        val samples = out.toArray(limit.toInt())
        val fullMs = if (durationMs > 0) durationMs else samples.size * 1000L / AnalysisMath.SAMPLE_RATE
        return Decoded(samples, fullMs, fullMs <= AnalysisMath.MAX_ANALYZED_SECONDS * 1000L)
    }

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
