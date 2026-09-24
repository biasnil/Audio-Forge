package com.biasnil.audioforge.tags

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.CannotWriteException
import org.jaudiotagger.tag.FieldDataInvalidException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** Why a tag read or write didn't work, for a message the user can act on. */
class TagEditException(
    val kind: Kind,
    /** For [Kind.InvalidValue]: which field (FieldKey name). */
    val field: String? = null,
    cause: Throwable? = null,
) : Exception(kind.name, cause) {
    enum class Kind {
        /** Not a format jaudiotagger can edit (e.g. Opus), or not really audio. */
        UnsupportedFormat,
        /** A value the format can't hold, e.g. a non-numeric track number. */
        InvalidValue,
        /** Anything else that stopped the write. */
        WriteFailed,
    }
}

/**
 * Reads and writes a file's tags with jaudiotagger -- the Android side's
 * TagLib. Works on a plain java.io.File (the caller copies the song to a
 * temp file and back, since Android hands out content:// URIs), with no
 * Android types, so it's unit tested on the JVM.
 *
 * Fields are identified by jaudiotagger FieldKey names ("TITLE",
 * "ALBUM_ARTIST", ...), which it maps to each format's own frames/keys.
 */
object TagFileEditor {

    /**
     * Always shown in the editor, in this order, like the desktop's manual
     * tag dialog -- so it looks the same from file to file. Other fields
     * appear only when the file has them (or the user adds them).
     */
    val CORE_KEYS: List<String> = listOf(
        FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.ALBUM_ARTIST,
        FieldKey.GENRE, FieldKey.YEAR, FieldKey.TRACK, FieldKey.COMPOSER, FieldKey.COMMENT,
    ).map { it.name }

    // Big binary/technical fields that make no sense as a one-line text box.
    private val HIDDEN_KEYS = setOf(FieldKey.COVER_ART.name)

    /**
     * Must be whole numbers. jaudiotagger doesn't check these for every
     * format, and other players misread e.g. a track number of "three".
     */
    private val NUMERIC_KEYS = setOf(
        FieldKey.TRACK, FieldKey.TRACK_TOTAL, FieldKey.DISC_NO, FieldKey.DISC_TOTAL, FieldKey.BPM,
    ).map { it.name }.toSet()

    init {
        // Android mode avoids java.awt/ImageIO code paths; and jaudiotagger's
        // logging is very chatty.
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** The editable fields: [values] (core fields always present, maybe blank) and what else could be added. */
    data class Fields(val values: LinkedHashMap<String, String>, val addableKeys: List<String>)

    fun read(file: File): Fields {
        val audioFile = open(file)
        val tag: Tag = audioFile.tag ?: audioFile.createDefaultTag()
        val values = LinkedHashMap<String, String>()
        val addable = ArrayList<String>()
        for (key in CORE_KEYS) values[key] = firstValue(tag, FieldKey.valueOf(key)).orEmpty()
        for (key in FieldKey.entries) {
            if (key.name in CORE_KEYS || key.name in HIDDEN_KEYS) continue
            val value = firstValue(tag, key) ?: continue // null = not supported by this format
            if (value.isNotEmpty()) values[key.name] = value else addable += key.name
        }
        return Fields(values, addable)
    }

    /**
     * Writes only what changed between [original] and [edited]: a blank
     * value deletes the field, anything else sets it. Untouched fields
     * aren't rewritten.
     */
    fun write(file: File, original: Map<String, String>, edited: Map<String, String>) {
        val audioFile = open(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        var changed = false
        for (keyName in original.keys + edited.keys) {
            val before = original[keyName].orEmpty().trim()
            val after = edited[keyName].orEmpty().trim()
            if (before == after) continue
            val key = runCatching { FieldKey.valueOf(keyName) }.getOrNull() ?: continue
            if (after.isNotEmpty() && keyName in NUMERIC_KEYS && !after.all { it.isDigit() }) {
                throw TagEditException(TagEditException.Kind.InvalidValue, keyName)
            }
            try {
                if (after.isEmpty()) tag.deleteField(key) else tag.setField(key, after)
            } catch (e: FieldDataInvalidException) {
                throw TagEditException(TagEditException.Kind.InvalidValue, keyName, e)
            } catch (e: IllegalArgumentException) {
                throw TagEditException(TagEditException.Kind.InvalidValue, keyName, e)
            } catch (e: UnsupportedOperationException) {
                throw TagEditException(TagEditException.Kind.InvalidValue, keyName, e)
            }
            changed = true
        }
        if (!changed) return
        try {
            audioFile.commit()
        } catch (e: CannotWriteException) {
            throw TagEditException(TagEditException.Kind.WriteFailed, cause = e)
        }
    }

    /**
     * Replaces the embedded cover art with [imageData] -- a full replace, not
     * an add, so the file doesn't end up with two front covers (the
     * desktop's WriteCoverArt()). [mimeType] must match the bytes
     * ("image/jpeg" or "image/png").
     */
    fun writeCover(file: File, imageData: ByteArray, mimeType: String) {
        val audioFile = open(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        try {
            when (tag) {
                // FLAC and Ogg store a FLAC picture block. jaudiotagger's generic
                // path would decode the image to measure it, which its Android
                // mode can't do -- so the block is built here, with the size
                // read from the image header.
                is FlacTag -> {
                    tag.deleteArtworkField()
                    tag.setField(pictureBlock(imageData, mimeType))
                }
                is VorbisCommentTag -> {
                    tag.deleteArtworkField()
                    val encoded = Base64.getEncoder().encodeToString(pictureBlock(imageData, mimeType).rawContent)
                    tag.setField(tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, encoded))
                }
                else -> { // MP3/WAV (ID3 APIC), M4A (covr)
                    val artwork = ArtworkFactory.getNew() // AndroidArtwork in Android mode
                    artwork.binaryData = imageData
                    artwork.mimeType = mimeType
                    artwork.pictureType = PictureTypes.DEFAULT_ID // front cover
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }
            }
        } catch (e: Exception) {
            throw TagEditException(TagEditException.Kind.UnsupportedFormat, cause = e)
        }
        try {
            audioFile.commit()
        } catch (e: CannotWriteException) {
            throw TagEditException(TagEditException.Kind.WriteFailed, cause = e)
        }
    }

    private fun pictureBlock(imageData: ByteArray, mimeType: String): MetadataBlockDataPicture {
        val (width, height) = imageDimensions(imageData) ?: (0 to 0)
        return MetadataBlockDataPicture(imageData, PictureTypes.DEFAULT_ID, mimeType, "", width, height, 0, 0)
    }

    /** Width x height from a PNG or JPEG header, without decoding the image. */
    internal fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        // PNG: 8-byte signature, then the IHDR chunk with width/height.
        if (bytes.size >= 24 && u8(0) == 0x89 && u8(1) == 'P'.code && u8(2) == 'N'.code && u8(3) == 'G'.code) {
            return u32(16) to u32(20)
        }
        // JPEG: walk the segments to the first start-of-frame marker.
        if (bytes.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (u8(i) != 0xFF) return null
                val marker = u8(i + 1)
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 || marker == 0xFF) {
                    i += if (marker == 0xFF) 1 else 2
                    continue
                }
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return u16(i + 7) to u16(i + 5)
                }
                i += 2 + u16(i + 2)
            }
        }
        return null
    }

    /** The embedded front cover's bytes, or null if there is none. */
    fun readCover(file: File): ByteArray? = open(file).tag?.firstArtwork?.binaryData

    /** Whether the editor should offer a number keyboard (and save rejects non-digits). */
    fun isNumeric(keyName: String): Boolean = keyName in NUMERIC_KEYS

    /** "ALBUM_ARTIST" -> "Album artist". */
    fun displayName(keyName: String): String =
        keyName.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun open(file: File): AudioFile = try {
        AudioFileIO.read(file)
    } catch (e: CannotReadException) {
        throw TagEditException(TagEditException.Kind.UnsupportedFormat, cause = e)
    } catch (e: Exception) {
        // jaudiotagger's other read exceptions (bad header, invalid frame...)
        throw TagEditException(TagEditException.Kind.UnsupportedFormat, cause = e)
    }

    /** The field's first value; "" if unset; null if this format doesn't have that field at all. */
    private fun firstValue(tag: Tag, key: FieldKey): String? = try {
        tag.getFirst(key).orEmpty()
    } catch (e: Exception) {
        null
    }
}
