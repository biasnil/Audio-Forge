package com.biasnil.audioforge.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Cover art for one track: embedded art first, then a cover.jpg-style image
 * next to the file (the desktop's fallback), decoded at roughly the size
 * it's shown at and cached in memory.
 */
class CoverArtLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    /** Tracks known to have no art, so they aren't re-read on every recomposition. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val _version = MutableStateFlow(0)
    /** Bumped when a cover changes, so anything showing art reloads it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun cached(track: Track, sizePx: Int): Bitmap? = cache.get(key(track, sizePx))

    /** Forgets everything cached for [uri] (after its cover was changed). */
    fun invalidate(uri: String) {
        val prefix = "$uri@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        synchronized(missing) { missing.removeAll { it.startsWith(prefix) } }
        _version.value++
    }

    suspend fun load(track: Track, sizePx: Int): Bitmap? {
        val key = key(track, sizePx)
        cache.get(key)?.let { return it }
        if (key in missing) return null

        // A radio station's cover is its logo from the web; its stream is no file to read art from.
        val station = track.station
        if (station != null) return station.favicon?.let { RemoteImages.load(it, sizePx) }
        val bitmap = withContext(Dispatchers.IO) {
            decodeEmbedded(track.uri, sizePx)
                ?: track.folderCoverUri?.let { decodeFile(it, sizePx) }
                ?: decodeFoundCover(track, sizePx)
        }
        if (bitmap != null) cache.put(key, bitmap) else missing += key
        return bitmap
    }

    private fun decodeEmbedded(uri: String, sizePx: Int): Bitmap? = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.embeddedPicture?.let { decodeSampled(it, sizePx) }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeFile(uri: String, sizePx: Int): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?.let { decodeSampled(it, sizePx) }
    } catch (e: Exception) {
        null
    }

    /** A cover found online for the album (Settings -> Library -> Find missing covers), if any. */
    private fun decodeFoundCover(track: Track, sizePx: Int): Bitmap? {
        if (track.album.isBlank()) return null
        val file = MetadataRepository.coverFile(File(context.filesDir, MetadataRepository.COVERS_DIR), track)
        if (!file.exists()) return null
        return try {
            decodeSampled(file.readBytes(), sizePx)
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes at the smallest power-of-two reduction that's still at least [sizePx]. */
    private fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun key(track: Track, sizePx: Int) = "${track.uri}@$sizePx"

    private companion object {
        fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
    }
}
