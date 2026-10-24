package com.biasnil.audioforge.edit

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream

/**
 * MP3 encoding with jump3r (LAME in plain Java, LGPL), which Android
 * itself can't do. Constant bitrate, so the file needs no header fixed up
 * afterwards. Set up the way jump3r's own LameEncoder does, minus its
 * javax.sound parts (which Android doesn't have).
 */
class Mp3Encoder(sampleRate: Int, private val channels: Int, bitrateKbps: Int, private val out: OutputStream) : AutoCloseable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var encoded = ByteArray(0)

    init {
        require(channels == 1 || channels == 2) { "MP3 holds 1 or 2 channels, not $channels" }
        val gain = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val mpg = MPGLib()
        val decoderInterface = Interface()
        val common = Common()
        lame.setModules(gain, bitStream, presets, quantizePvt, quantize, vbrTag, version, id3, mpg)
        bitStream.setModules(gain, mpg, version, vbrTag)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        mpg.setModules(decoderInterface, common)
        decoderInterface.setModules(vbrTag, common)

        flags = lame.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.brate = bitrateKbps
        flags.quality = QUALITY
        // The tags are written afterwards (jaudiotagger), and CBR needs no Xing header.
        id3.id3tag_init(flags)
        flags.write_id3tag_automatic = false
        flags.bWriteVbrTag = false
        check(lame.lame_init_params(flags) >= 0) { "MP3 encoder refused $sampleRate Hz, $channels channels, $bitrateKbps kbps" }
    }

    /** Encodes [count] interleaved 16-bit samples from [samples]. */
    fun encode(samples: ShortArray, count: Int) {
        val frames = count / channels
        if (frames == 0) return
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
            // LAME's worst case: 1.25 x samples + 7200 bytes.
            encoded = ByteArray(frames * 5 / 4 + 7200)
        }
        // LAME's int input is full 32-bit scale.
        if (channels == 2) {
            for (i in 0 until frames) {
                left[i] = samples[2 * i].toInt() shl 16
                right[i] = samples[2 * i + 1].toInt() shl 16
            }
        } else {
            for (i in 0 until frames) {
                left[i] = samples[i].toInt() shl 16
                right[i] = left[i]
            }
        }
        val bytes = lame.lame_encode_buffer_int(flags, left, right, frames, encoded, 0, encoded.size)
        check(bytes >= 0) { "MP3 encoding failed ($bytes)" }
        out.write(encoded, 0, bytes)
    }

    /** Writes what's left in the encoder. Call once, at the end. */
    fun finish() {
        val buffer = ByteArray(7200)
        val bytes = lame.lame_encode_flush(flags, buffer, 0, buffer.size)
        if (bytes > 0) out.write(buffer, 0, bytes)
    }

    override fun close() {
        lame.lame_close(flags)
    }

    companion object {
        /** LAME's -q 3: near its best, at a speed a phone copes with. */
        private const val QUALITY = 3

        /** The MPEG-1 Layer III bitrates (kbps); a song is re-encoded at the nearest one up from its own. */
        private val BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

        /** The bitrate to save at: the song's own (rounded up to one MP3 has), so the file stays the same size; 320 when unknown. */
        fun bitrateFor(originalKbps: Int): Int =
            if (originalKbps <= 0) 320 else BITRATES.firstOrNull { it >= originalKbps } ?: 320
    }
}
