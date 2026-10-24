package com.biasnil.audioforge.edit

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A song decoded for editing: 16-bit PCM, its own sample rate and channels,
 * in a file in the app's cache (a long song would be too big to keep in
 * memory), plus [peaks]: the loudest sample of every [PEAK_FRAMES] frames,
 * for drawing the waveform.
 */
class PcmSource(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val peaks: FloatArray,
) {
    val bytesPerFrame: Int get() = channels * 2
    val durationMs: Long get() = frames * 1000 / sampleRate

    fun framesToMs(frames: Long): Long = frames * 1000 / sampleRate
    fun msToFrames(ms: Long): Long = ms * sampleRate / 1000

    /** The loudest sample (0..1) in the parts of the song [edit] keeps, before its volume change. */
    fun peakOf(edit: AudioEdit): Float {
        var peak = 0f
        for (span in edit.spans) {
            val first = (span.start / PEAK_FRAMES).toInt()
            val last = ((span.end - 1) / PEAK_FRAMES).toInt().coerceAtMost(peaks.lastIndex)
            for (i in first..last) if (peaks[i] > peak) peak = peaks[i]
        }
        return peak
    }

    /** Opens the PCM for reading frames [start, start + count) into [into] (interleaved). */
    fun reader(): Reader = Reader(RandomAccessFile(file, "r"))

    inner class Reader(private val raf: RandomAccessFile) : AutoCloseable {
        private var bytes = ByteArray(0)

        /** Reads up to [count] frames from [start]; returns how many frames were read. */
        fun read(start: Long, count: Int, into: ShortArray): Int {
            val wanted = count.coerceAtMost((frames - start).coerceAtLeast(0).toInt())
            if (wanted <= 0) return 0
            val size = wanted * bytesPerFrame
            if (bytes.size < size) bytes = ByteArray(size)
            raf.seek(start * bytesPerFrame)
            raf.readFully(bytes, 0, size)
            ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(into, 0, wanted * channels)
            return wanted
        }

        override fun close() = raf.close()
    }

    companion object {
        /** Frames per waveform peak (about 6 ms at 44.1 kHz). */
        const val PEAK_FRAMES = 256

        /**
         * Decodes [uri] into [file]. [onProgress] gets 0..1. Throws if the
         * song can't be decoded. Blocks: run on Dispatchers.IO.
         */
        suspend fun decode(context: Context, uri: Uri, file: File, onProgress: (Float) -> Unit): PcmSource {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("No audio in $uri")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    return decodeLoop(extractor, codec, format, durationUs, file, onProgress)
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }

        private suspend fun decodeLoop(
            extractor: MediaExtractor,
            codec: MediaCodec,
            format: MediaFormat,
            durationUs: Long,
            file: File,
            onProgress: (Float) -> Unit,
        ): PcmSource {
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var frames = 0L
            val peaks = PeakBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var iterations = 0
            var chunk = ByteArray(0)
            BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                while (true) {
                    if (++iterations % 64 == 0) {
                        currentCoroutineContext().ensureActive()
                        if (durationUs > 0) onProgress((extractor.sampleTime.coerceAtLeast(0).toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val read = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
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
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            // Everything is stored as 16-bit little-endian.
                            val samples = if (floatPcm) info.size / 4 else info.size / 2
                            if (chunk.size < samples * 2) chunk = ByteArray(samples * 2)
                            val le = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                            if (floatPcm) {
                                val floats = buffer.asFloatBuffer()
                                for (i in 0 until samples) {
                                    val s = (floats.get(i).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                                    le.putShort(i * 2, s)
                                    peaks.add(s, channels)
                                }
                            } else {
                                val shorts = buffer.asShortBuffer()
                                for (i in 0 until samples) {
                                    val s = shorts.get(i)
                                    le.putShort(i * 2, s)
                                    peaks.add(s, channels)
                                }
                            }
                            out.write(chunk, 0, samples * 2)
                            frames += samples / channels
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            onProgress(1f)
            return PcmSource(file, sampleRate, channels, frames, peaks.toArray())
        }
    }

    /** The loudest sample of every [PEAK_FRAMES] frames. */
    private class PeakBuilder {
        private var data = FloatArray(4096)
        private var size = 0
        private var samplesInPeak = 0
        private var current = 0

        fun add(sample: Short, channels: Int) {
            val level = abs(sample.toInt())
            if (level > current) current = level
            if (++samplesInPeak == PEAK_FRAMES * channels) flush()
        }

        private fun flush() {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = current / 32768f
            current = 0
            samplesInPeak = 0
        }

        fun toArray(): FloatArray {
            if (samplesInPeak > 0) flush()
            return data.copyOf(size)
        }
    }
}
