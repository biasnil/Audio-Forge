package com.biasnil.audioforge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Show artwork from the web (podcast search and feeds), scaled down and kept
 * in memory. The app has no image library; this is all it needs.
 */
object RemoteImages {
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    /** URLs that failed, so a list doesn't retry them on every scroll. */
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun cached(url: String, sizePx: Int): Bitmap? = cache.get("$url@$sizePx")

    suspend fun load(url: String, sizePx: Int): Bitmap? {
        val key = "$url@$sizePx"
        cache.get(key)?.let { return it }
        if (url in failed) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = PodcastRepository.get(url) { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (bitmap != null) cache.put(key, bitmap) else failed += url
        return bitmap
    }

    private const val CACHE_KB = 16 * 1024
}
