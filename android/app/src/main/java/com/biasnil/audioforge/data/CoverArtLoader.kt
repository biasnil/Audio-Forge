package com.biasnil.audioforge.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cover art for one track: embedded art first, then a cover.jpg-style image
 * next to the file (the desktop's fallback), then a cover found online.
 * The music files are only read, never changed.
 *
 * Built for big libraries:
 * - Songs of one album share one cover ([coverKey]), so 800 songs in 100
 *   albums decode 100 pictures, not 800.
 * - Phone-library songs use Android's own album-art thumbnails (cached by
 *   the system); other files get a small JPEG copy in the app's cache, so
 *   showing a cover rarely means opening a music file again.
 * - At most [PARALLEL_DECODES] covers decode at once, and a row scrolled
 *   away before its turn is simply skipped.
 * - Covers are kept in memory at the size they're shown at (rounded up to
 *   a few fixed sizes, so lists of slightly different sizes share them).
 */
class CoverArtLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    /** Covers known to be missing, so they aren't looked for on every recomposition. */
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /** One decode per cover at a time: other rows of the same album wait and then hit the cache. */
    private val locks = ConcurrentHashMap<String, Mutex>()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoding = Dispatchers.IO.limitedParallelism(PARALLEL_DECODES)
    private val diskDir = File(context.cacheDir, DISK_DIR)

    private val _version = MutableStateFlow(0)
    /** Bumped when a cover changes, so anything showing art reloads it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun cached(track: Track, sizePx: Int): Bitmap? = cache.get(memoryKey(track, sizePx))

    /** Forgets the cover of [track]'s album (after a cover was changed or found). */
    fun invalidate(track: Track) {
        val key = coverKey(track)
        cache.snapshot().keys.filter { it.startsWith("$key@") }.forEach { cache.remove(it) }
        synchronized(missing) { missing.removeAll { it.startsWith("$key@") } }
        diskDir.listFiles { file -> file.name.startsWith(diskName(key)) }?.forEach { it.delete() }
        _version.value++
    }

    /** Forgets every cover, in memory and on disk (Settings -> Library -> Refresh library). */
    fun clearAll() {
        cache.evictAll()
        missing.clear()
        diskDir.deleteRecursively()
        _version.value++
    }

    suspend fun load(track: Track, sizePx: Int): Bitmap? {
        val key = memoryKey(track, sizePx)
        cache.get(key)?.let { return it }
        if (key in missing) return null

        // A radio station's cover is its logo from the web; its stream is no file to read art from.
        val station = track.station
        if (station != null) return station.favicon?.let { RemoteImages.load(it, sizePx) }

        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            // Another row of the same album may have just decoded it.
            cache.get(key)?.let { return@withLock it }
            if (key in missing) return@withLock null
            val bitmap = withContext(decoding) { decode(track, bucket(sizePx)) }
            if (bitmap != null) {
                bitmap.prepareToDraw() // start the upload to the GPU before it's drawn
                cache.put(key, bitmap)
            } else {
                missing += key
            }
            bitmap
        }
    }

    private fun decode(track: Track, sizePx: Int): Bitmap? {
        systemThumbnail(track.uri, sizePx)?.let { return it }
        val disk = File(diskDir, diskName(coverKey(track)) + "_$sizePx.jpg")
        val none = File(diskDir, diskName(coverKey(track)) + "_$sizePx.none")
        if (disk.exists()) decodeSampled(disk.readBytes(), sizePx)?.let { return it }
        if (none.exists()) return null
        val bitmap = decodeEmbedded(track.uri, sizePx)
            ?: track.folderCoverUri?.let { decodeFile(it, sizePx) }
            ?: decodeFoundCover(track, sizePx)
        // Remember the result on disk, so the music file isn't opened for this again.
        try {
            diskDir.mkdirs()
            if (bitmap != null) {
                disk.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_QUALITY, it) }
            } else if (isMediaStore(track.uri) || track.folderCoverUri == null) {
                none.createNewFile()
            }
        } catch (_: Exception) {
            // A full disk only costs speed.
        }
        return bitmap
    }

    /**
     * Android's own album-art thumbnail for a phone-library song: fast, and
     * cached by the system. Null for added-folder files or songs without art.
     */
    private fun systemThumbnail(uri: String, sizePx: Int): Bitmap? {
        if (!isMediaStore(uri)) return null
        return try {
            fitTo(context.contentResolver.loadThumbnail(Uri.parse(uri), Size(sizePx, sizePx), null), sizePx)
        } catch (_: Exception) {
            null
        }
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

    /** Decodes at the smallest power-of-two reduction that's still at least [sizePx], then fits it to [sizePx]. */
    private fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { fitTo(it, sizePx) }
    }

    /** Scales [bitmap] down so its shorter side is [sizePx] (sampling can leave it up to twice that). */
    private fun fitTo(bitmap: Bitmap, sizePx: Int): Bitmap {
        val shorter = minOf(bitmap.width, bitmap.height)
        if (shorter <= sizePx * 5 / 4) return bitmap
        val scale = sizePx.toFloat() / shorter
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun memoryKey(track: Track, sizePx: Int) = "${coverKey(track)}@${bucket(sizePx)}"

    private fun isMediaStore(uri: String) = uri.startsWith(MediaStore.AUTHORITY_URI.toString()) ||
        uri.startsWith("content://media/")

    private companion object {
        const val PARALLEL_DECODES = 3
        const val DISK_DIR = "covers"
        const val DISK_QUALITY = 90

        fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()

        /** Fixed sizes covers are kept at, so a 40 dp and a 44 dp row share one picture. */
        val BUCKETS = intArrayOf(96, 128, 192, 256, 384, 512, 768, 1024)

        fun bucket(sizePx: Int): Int = BUCKETS.firstOrNull { it >= sizePx } ?: sizePx

        fun diskName(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/**
 * Which cover a track shows: its album's (songs of one album, in one
 * folder, share it), or its own when it has no album name.
 */
internal fun coverKey(track: Track): String =
    if (track.album.isBlank()) "track:${track.uri}" else "album:${track.folderKey}|${track.album.trim().lowercase()}"
