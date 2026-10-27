package com.biasnil.audioforge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Show artwork from the web (podcast search and feeds, charts, playlist
 * thumbnails), scaled down and kept in memory. The app has no image
 * library; this is all it needs. A few load at a time, so scrolling fast
 * through a long list doesn't start hundreds of downloads: a row scrolled
 * away cancels its load (the caller's coroutine) before it even starts.
 */
object RemoteImages {
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    /** URLs that failed, and when, so a list doesn't retry them on every scroll (for a while). */
    private val failed = ConcurrentHashMap<String, Long>()
    private val downloads = Semaphore(MAX_PARALLEL)

    fun cached(url: String, sizePx: Int): Bitmap? = cache.get("$url@$sizePx")

    suspend fun load(url: String, sizePx: Int): Bitmap? {
        val key = "$url@$sizePx"
        cache.get(key)?.let { return it }
        failed[url]?.let { at ->
            if (SystemClock.elapsedRealtime() - at < RETRY_FAILED_AFTER_MS) return null
            failed.remove(url)
        }
        val bitmap = downloads.withPermit {
            cache.get(key)?.let { return@withPermit it } // loaded by another row meanwhile
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = PodcastRepository.get(url, timeoutMs = TIMEOUT_MS) { it.readBytes() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
        }
        if (bitmap != null) cache.put(key, bitmap) else failed[url] = SystemClock.elapsedRealtime()
        return bitmap
    }

    private const val CACHE_KB = 16 * 1024
    private const val MAX_PARALLEL = 4
    private const val TIMEOUT_MS = 10_000
    private const val RETRY_FAILED_AFTER_MS = 10 * 60_000L
}
