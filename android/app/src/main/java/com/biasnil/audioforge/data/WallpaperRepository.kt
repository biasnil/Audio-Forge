package com.biasnil.audioforge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.util.UUID

/**
 * The live video wallpaper settings (the desktop's WallpaperLibrary): one
 * global video plus per-song assignments, saved with the other settings.
 * Videos are picked with the system file picker and kept readable across
 * restarts with a persisted permission, released once nothing uses them.
 */
class WallpaperRepository(private val context: Context, private val store: AppStore) {

    /**
     * The desktop's resolution rule: the first assignment containing the
     * song wins, otherwise the global video; null = no wallpaper.
     */
    fun resolveVideoFor(trackUri: String, settings: AppSettings = store.settings.value): String? =
        settings.wallpapers.firstOrNull { trackUri in it.trackUris }?.videoUri
            ?: settings.globalWallpaperUri.ifEmpty { null }

    fun setGlobal(videoUri: Uri) {
        keepAccess(videoUri)
        val old = store.settings.value.globalWallpaperUri
        store.update { it.copy(globalWallpaperUri = videoUri.toString()) }
        releaseIfUnused(old)
    }

    fun clearGlobal() {
        val old = store.settings.value.globalWallpaperUri
        store.update { it.copy(globalWallpaperUri = "") }
        releaseIfUnused(old)
    }

    fun addEntry(videoUri: Uri, trackUris: List<String>) {
        keepAccess(videoUri)
        val entry = WallpaperEntry(UUID.randomUUID().toString(), videoUri.toString(), trackUris)
        store.update { it.copy(wallpapers = it.wallpapers + entry) }
    }

    fun setEntryTracks(id: String, trackUris: List<String>) = store.update { settings ->
        settings.copy(wallpapers = settings.wallpapers.map { if (it.id == id) it.copy(trackUris = trackUris) else it })
    }

    fun removeEntry(id: String) {
        val removed = store.settings.value.wallpapers.firstOrNull { it.id == id } ?: return
        store.update { settings -> settings.copy(wallpapers = settings.wallpapers.filterNot { it.id == id }) }
        releaseIfUnused(removed.videoUri)
    }

    /** The video's file name, for the Wallpapers tab; falls back to the URI's last segment. */
    fun displayName(videoUri: String): String {
        val uri = Uri.parse(videoUri)
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
            // Deleted, or access lost -- the fallback below still identifies it.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: videoUri
    }

    private fun keepAccess(videoUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(videoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't keep access to $videoUri", e)
        }
    }

    /** Called after the settings update, so [videoUri] is checked against what's left. */
    private fun releaseIfUnused(videoUri: String) {
        if (videoUri.isEmpty()) return
        val settings = store.settings.value
        if (settings.globalWallpaperUri == videoUri || settings.wallpapers.any { it.videoUri == videoUri }) return
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(videoUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not held -- nothing to release.
        }
    }

    private companion object {
        const val TAG = "WallpaperRepository"
    }
}
