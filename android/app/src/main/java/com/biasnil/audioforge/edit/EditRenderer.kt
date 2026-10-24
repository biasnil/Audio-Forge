package com.biasnil.audioforge.edit

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** What an edited song is saved as: M4A (AAC, what Android can encode) or WAV (as-is, for WAV songs). */
enum class EditFormat(val extension: String, val mimeType: String) {
    M4a("m4a", "audio/mp4"),
    Wav("wav", "audio/wav"),
}

/**
 * Writes the edited song ([edit] applied to [source]) to [out]. Blocks:
 * run on Dispatchers.IO. [onProgress] gets 0..1.
 */
object EditRenderer {
    private const val CHUNK_FRAMES = 4096
    private const val AAC_BITRATE = 256_000

    suspend fun render(source: PcmSource, edit: AudioEdit, format: EditFormat, out: File, onProgress: (Float) -> Unit) {
        when (format) {
            EditFormat.Wav -> writeWav(source, edit, out, onProgress)
            EditFormat.M4a -> writeM4a(source, edit, out, onProgress)
        }
    }

    /** The edited song's samples, chunk by chunk, with the volume change applied (clipped, never wrapped). */
    private suspend fun forEachChunk(source: PcmSource, edit: AudioEdit, onProgress: (Float) -> Unit, block: (ShortArray, Int) -> Unit) {
        val gain = gainFactor(edit.gainDb)
        val buffer = ShortArray(CHUNK_FRAMES * source.channels)
        val total = edit.length.coerceAtLeast(1)
        var done = 0L
        source.reader().use { reader ->
            for (span in edit.spans) {
                var at = span.start
                while (at < span.end) {
                    currentCoroutineContext().ensureActive()
                    val frames = reader.read(at, minOf(CHUNK_FRAMES.toLong(), span.end - at).toInt(), buffer)
                    if (frames <= 0) break
                    val samples = frames * source.channels
                    if (gain != 1f) {
                        for (i in 0 until samples) buffer[i] = (buffer[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    block(buffer, samples)
                    at += frames
                    done += frames
                    onProgress(done.toFloat() / total)
                }
            }
        }
    }

    private suspend fun writeWav(source: PcmSource, edit: AudioEdit, out: File, onProgress: (Float) -> Unit) {
        val dataBytes = edit.length * source.bytesPerFrame
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            stream.write(wavHeader(source.sampleRate, source.channels, dataBytes))
            val bytes = ByteArray(CHUNK_FRAMES * source.bytesPerFrame)
            forEachChunk(source, edit, onProgress) { samples, count ->
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
                stream.write(bytes, 0, count * 2)
            }
        }
    }

    private suspend fun writeM4a(source: PcmSource, edit: AudioEdit, out: File, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, source.sampleRate, source.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * source.bytesPerFrame)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var track = -1
            val info = MediaCodec.BufferInfo()
            var framesQueued = 0L

            // Takes whatever the encoder has finished; true once it has sent its last buffer.
            fun drain(wait: Long): Boolean {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, wait)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val data = codec.getOutputBuffer(index)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !config && muxerStarted) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                    }
                }
            }

            fun queue(samples: ShortArray, count: Int, last: Boolean) {
                var offset = 0
                while (offset < count || (last && offset == count)) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index < 0) {
                        drain(0)
                        continue
                    }
                    val input = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    input.clear()
                    // Whole frames only, so the channels never swap places.
                    val n = minOf(count - offset, input.capacity() / 2 / source.channels * source.channels)
                    input.asShortBuffer().put(samples, offset, n)
                    val timeUs = framesQueued * 1_000_000L / source.sampleRate
                    val end = last && offset + n == count
                    codec.queueInputBuffer(index, 0, n * 2, timeUs, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    framesQueued += n / source.channels
                    offset += n
                    drain(0)
                    if (end) break
                }
            }

            forEachChunk(source, edit, onProgress) { samples, count -> queue(samples, count, last = false) }
            queue(ShortArray(0), 0, last = true)
            while (!drain(10_000)) currentCoroutineContext().ensureActive()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** A 44-byte RIFF/WAVE header for 16-bit PCM. */
    fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        return header.array()
    }
}
