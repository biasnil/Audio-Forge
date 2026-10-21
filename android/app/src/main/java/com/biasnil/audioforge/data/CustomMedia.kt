package com.biasnil.audioforge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.util.UUID

/** The Now Playing visualizer's look (Settings -> Visualizer). Colours follow the song's cover. */
enum class VisualizerStyle {
    ClassicBars, Spectrum, NeonBars,
    LineWave, RetroWave, FlowingWave,
    Radial, Particles, Geometric,
    Disc,
}

/**
 * A GIF or short looping video shown by the visualizer instead of its
 * style: for the songs in [trackUris], or for every song without its own
 * when [global]. [round] crops it to a circle; [spin] turns it while
 * music plays, like a record.
 */
data class CustomMediaEntry(
    val id: String,
    val uri: String,
    val isVideo: Boolean,
    val round: Boolean = true,
    val spin: Boolean = true,
    val trackUris: List<String> = emptyList(),
    val global: Boolean = false,
)

/**
 * Settings -> Visualizer -> custom GIFs and videos: one global item plus
 * per-song assignments, like the video wallpapers ([WallpaperRepository]).
 * Files are picked with the system picker and kept readable with a
 * persisted permission, released once nothing uses them.
 */
class CustomMediaRepository(private val context: Context, private val store: AppStore) {

    /** The song's own item (the first that lists it), else the global one; null = show the style. */
    fun resolveFor(trackUri: String, settings: AppSettings = store.settings.value): CustomMediaEntry? =
        settings.customMedia.firstOrNull { !it.global && trackUri in it.trackUris }
            ?: settings.customMedia.firstOrNull { it.global }

    /** Uses [uri] as the global item (replacing the old one, keeping its shape and spin). */
    fun setGlobal(uri: Uri) {
        keepAccess(uri)
        val old = store.settings.value.customMedia.firstOrNull { it.global }
        val entry = CustomMediaEntry(
            id = old?.id ?: UUID.randomUUID().toString(),
            uri = uri.toString(),
            isVideo = isVideo(uri),
            round = old?.round ?: true,
            spin = old?.spin ?: true,
            global = true,
        )
        store.update { settings -> settings.copy(customMedia = settings.customMedia.filterNot { it.global } + entry) }
        old?.let { releaseIfUnused(it.uri) }
    }

    fun addEntry(uri: Uri, trackUris: List<String>) {
        keepAccess(uri)
        val entry = CustomMediaEntry(UUID.randomUUID().toString(), uri.toString(), isVideo(uri), trackUris = trackUris)
        store.update { it.copy(customMedia = it.customMedia + entry) }
    }

    fun update(id: String, change: (CustomMediaEntry) -> CustomMediaEntry) = store.update { settings ->
        settings.copy(customMedia = settings.customMedia.map { if (it.id == id) change(it) else it })
    }

    fun remove(id: String) {
        val removed = store.settings.value.customMedia.firstOrNull { it.id == id } ?: return
        store.update { settings -> settings.copy(customMedia = settings.customMedia.filterNot { it.id == id }) }
        releaseIfUnused(removed.uri)
    }

    /** The file's name, for Settings; falls back to the URI's last segment. */
    fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        try {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
            // Deleted, or access lost -- the fallback below still identifies it.
        }
        return parsed.lastPathSegment?.substringAfterLast('/') ?: uri
    }

    private fun isVideo(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true

    private fun keepAccess(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't keep access to $uri", e)
        }
    }

    /** Called after the settings update, so [uri] is checked against what's left. */
    private fun releaseIfUnused(uri: String) {
        val settings = store.settings.value
        if (settings.customMedia.any { it.uri == uri } || settings.globalWallpaperUri == uri ||
            settings.wallpapers.any { it.videoUri == uri }
        ) return
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not held -- nothing to release.
        }
    }

    private companion object {
        const val TAG = "CustomMediaRepository"
    }
}
