package com.biasnil.audioforge.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Audiobook chapters, read from the files themselves (see Mp4Chapters.kt)
 * and kept in memory: reading a book's chapter table takes a moment.
 */
class ChapterRepository(private val context: Context) {
    private val cache = HashMap<String, List<Chapter>>()

    /** [uri]'s chapters; empty for non-MP4 files or files without chapters. */
    suspend fun chaptersFor(uri: String): List<Chapter> {
        cache[uri]?.let { return it }
        val chapters = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { stream ->
                        val channel = stream.channel
                        readMp4Chapters(object : ByteSource {
                            override val size: Long = channel.size()

                            override fun read(offset: Long, length: Int): ByteArray {
                                val buffer = ByteBuffer.allocate(length.coerceAtMost((size - offset).coerceAtLeast(0).toInt()))
                                var at = offset
                                while (buffer.hasRemaining()) {
                                    val read = channel.read(buffer, at)
                                    if (read <= 0) break
                                    at += read
                                }
                                return buffer.array().copyOf(buffer.position())
                            }
                        })
                    }
                }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read chapters of $uri", e)
                emptyList()
            }
        }
        cache[uri] = chapters
        return chapters
    }

    private companion object {
        const val TAG = "ChapterRepository"
    }
}
