package com.biasnil.audioforge.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Copies [file] into the phone's music library at [relativePath] (for
 * example "Audiobooks/Jane Austen - Emma/"; Audio may go under Music/,
 * Audiobooks/, Podcasts/ and a few other folders) as [displayName]. The
 * file is the app's own, so it can be retagged later without asking. It
 * only shows up once it's complete (IS_PENDING). Null if it couldn't be
 * saved; nothing half-written is left behind.
 */
fun saveToPhoneLibrary(context: Context, file: File, relativePath: String, displayName: String, mimeType: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("No output stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}
