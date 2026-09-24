package com.biasnil.audioforge.tags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Edits a track's tags in place. Android hands out content:// URIs, not
 * file paths, so the song is copied to a temp file, edited there with
 * [TagFileEditor], then written back over the original.
 *
 * The caller must already hold write access (MediaStore write request, or
 * a folder's persisted write permission); without it the write-back throws
 * SecurityException.
 */
class TagEditor(private val context: Context) {

    private val resolver = context.contentResolver

    suspend fun read(track: Track): TagFileEditor.Fields = withContext(Dispatchers.IO) {
        val temp = copyToTemp(track)
        try {
            TagFileEditor.read(temp)
        } finally {
            temp.delete()
        }
    }

    /** Throws [TagEditException], [SecurityException] or [IOException]; on any of them the original file is untouched. */
    suspend fun write(track: Track, original: Map<String, String>, edited: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val temp = copyToTemp(track)
            try {
                TagFileEditor.write(temp, original, edited) // fails before the original is touched
                writeBack(Uri.parse(track.uri), temp)
            } finally {
                temp.delete()
            }
        }

    /** Embeds [image] as [track]'s front cover (see [prepareCoverImage]). Same guarantees as [write]. */
    suspend fun writeCover(track: Track, image: CoverImage) = withContext(Dispatchers.IO) {
        val temp = copyToTemp(track)
        try {
            TagFileEditor.writeCover(temp, image.bytes, image.mimeType)
            writeBack(Uri.parse(track.uri), temp)
        } finally {
            temp.delete()
        }
    }

    /** Image bytes ready to embed. */
    class CoverImage(val bytes: ByteArray, val mimeType: String)

    /**
     * Reads a picked image. JPEG/PNG up to [MAX_COVER_PX] are embedded as-is
     * (like the desktop); anything bigger, or another format (HEIC, WebP...),
     * is scaled down and re-encoded as JPEG -- a phone photo can be 10 MB,
     * which would otherwise be copied into every song it's used for.
     */
    suspend fun prepareCoverImage(imageUri: Uri): CoverImage = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: throw IOException("Couldn't open $imageUri")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        val mime = bounds.outMimeType
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if ((mime == "image/jpeg" || mime == "image/png") && largest <= MAX_COVER_PX) {
            return@withContext CoverImage(bytes, mime)
        }
        var sample = 1
        while (largest / (sample * 2) >= MAX_COVER_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Couldn't decode image")
        val scale = MAX_COVER_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        CoverImage(out.toByteArray(), "image/jpeg")
    }

    private fun copyToTemp(track: Track): File {
        // jaudiotagger picks the format by extension.
        val extension = track.fileName.substringAfterLast('.', "").ifEmpty { "mp3" }
        val directory = File(context.cacheDir, "tag-edit").apply { mkdirs() }
        val temp = File.createTempFile("edit", ".$extension", directory)
        try {
            val input = resolver.openInputStream(Uri.parse(track.uri))
                ?: throw IOException("Couldn't open ${track.fileName}")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return temp
    }

    /**
     * Overwrites [uri] with [source] and trims it to the new length -- a
     * shorter tag block mustn't leave the old file's tail behind (not every
     * provider honors the "truncate" open mode, so it's done explicitly).
     */
    private fun writeBack(uri: Uri, source: File) {
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Couldn't open $uri for writing")
        descriptor.use {
            // Not closed separately: closing the descriptor (use) closes it.
            val output = FileOutputStream(it.fileDescriptor)
            val channel = output.channel
            channel.position(0)
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
            channel.truncate(source.length())
        }
    }

    private companion object {
        const val MAX_COVER_PX = 1200
    }
}
