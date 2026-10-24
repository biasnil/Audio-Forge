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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** What an edited song is saved as: the song's own format where the app can write it. */
enum class EditFormat(val extension: String, val mimeType: String) {
    M4a("m4a", "audio/mp4"),
    Wav("wav", "audio/wav"),
    /** jump3r (LAME): Android has no MP3 encoder of its own. */
    Mp3("mp3", "audio/mpeg"),
    /** Android's own FLAC encoder: still lossless. */
    Flac("flac", "audio/flac"),
}

/**
 * Writes the edited song ([edit] applied to [source]) to [out]. Blocks:
 * run on Dispatchers.IO. [onProgress] gets 0..1.
 */
object EditRenderer {
    private const val CHUNK_FRAMES = 4096
    private const val AAC_BITRATE = 256_000

    suspend fun render(
        source: PcmSource,
        edit: AudioEdit,
        format: EditFormat,
        out: File,
        mp3BitrateKbps: Int = 320,
        onProgress: (Float) -> Unit,
    ) {
        when (format) {
            EditFormat.Wav -> writeWav(source, edit, out, onProgress)
            EditFormat.M4a -> writeM4a(source, edit, out, onProgress)
            EditFormat.Mp3 -> writeMp3(source, edit, out, mp3BitrateKbps, onProgress)
            EditFormat.Flac -> writeFlac(source, edit, out, onProgress)
        }
    }

    private suspend fun writeMp3(source: PcmSource, edit: AudioEdit, out: File, bitrateKbps: Int, onProgress: (Float) -> Unit) {
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            Mp3Encoder(source.sampleRate, source.channels, bitrateKbps, stream).use { encoder ->
                forEachChunk(source, edit, onProgress) { samples, count -> encoder.encode(samples, count) }
                encoder.finish()
            }
        }
    }

    /**
     * FLAC with Android's encoder: its first buffer is the file's header
     * ("fLaC" and the stream info), then the audio frames, written as they come.
     */
    private suspend fun writeFlac(source: PcmSource, edit: AudioEdit, out: File, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, source.sampleRate, source.channels).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * source.bytesPerFrame)
        }
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
            encodeWith(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC), format, source, edit, onProgress) { data, info ->
                val bytes = ByteArray(info.size)
                data.get(bytes)
                stream.write(bytes)
            }
        }
        // Android's encoder leaves the song's length out of the header (0 = unknown), which
        // leaves players without a duration or seeking: put it in.
        setFlacTotalSamples(out, edit.length)
    }

    /** Writes [frames] into a FLAC file's STREAMINFO (its 36-bit total-samples field). */
    fun setFlacTotalSamples(file: File, frames: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(8)
            raf.readFully(head)
            // "fLaC", then a STREAMINFO block (type 0) of 34 bytes.
            if (String(head, 0, 4, Charsets.US_ASCII) != "fLaC" || (head[4].toInt() and 0x7F) != 0) return
            val at = 8L + 13 // byte 13 of STREAMINFO holds the bits-per-sample end and the top 4 bits of the count
            raf.seek(at)
            val top = raf.read() and 0xF0
            raf.seek(at)
            raf.write(top or ((frames ushr 32).toInt() and 0x0F))
            raf.writeInt((frames and 0xFFFFFFFFL).toInt())
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
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            encodeWith(
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC), format, source, edit, onProgress,
                onFormat = { outputFormat ->
                    track = muxer.addTrack(outputFormat)
                    muxer.start()
                },
                skipConfig = true,
            ) { data, info -> muxer.writeSampleData(track, data, info) }
        } finally {
            if (track >= 0) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /**
     * Feeds the edited song through [codec] (an encoder), handing each
     * finished buffer to [sink] (positioned on its data). The codec's own
     * setup buffer is passed on too, unless [skipConfig] (the MP4 muxer
     * takes it from [onFormat]'s format instead).
     */
    private suspend fun encodeWith(
        codec: MediaCodec,
        format: MediaFormat,
        source: PcmSource,
        edit: AudioEdit,
        onProgress: (Float) -> Unit,
        onFormat: (MediaFormat) -> Unit = {},
        skipConfig: Boolean = false,
        sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var framesQueued = 0L

            // Takes whatever the encoder has finished; true once it has sent its last buffer.
            fun drain(wait: Long): Boolean {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, wait)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                        index >= 0 -> {
                            val data = codec.getOutputBuffer(index)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !(config && skipConfig)) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                sink(data, info)
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
