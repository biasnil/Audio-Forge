package com.biasnil.audioforge.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The music library: the phone's music (MediaStore) plus added folders,
 * scanned in the background, and the playlists built from those tracks.
 * [scope] must run on the main thread; scanning itself hops to IO.
 */
class LibraryRepository(
    private val context: Context,
    private val store: AppStore,
    private val scope: CoroutineScope,
) {
    private val mediaStore = MediaStoreSource(context.contentResolver)
    private val folderSource = FolderSource(context)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(checkAudioPermission())
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private var tracksByUri: Map<String, Track> = emptyMap()
    private var scanJob: Job? = null
    private var scanGeneration = 0

    /** Full rescan: picks up new files and drops ones that are gone. */
    fun refresh() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        _scanning.value = true
        scanJob = scope.launch {
            try {
                val settings = store.settings.value
                val hasPermission = checkAudioPermission()
                _hasAudioPermission.value = hasPermission
                val merged = withContext(Dispatchers.IO) {
                    val phone = if (settings.includePhoneLibrary && hasPermission) mediaStore.queryMusic() else emptyList()
                    val folders = settings.folders.flatMap { folderSource.scan(it) }
                    mergeSources(phone, folders)
                }
                tracksByUri = merged.associateBy { it.uri }
                _tracks.value = merged
            } catch (e: CancellationException) {
                throw e // superseded by a newer refresh()
            } catch (e: Exception) {
                // e.g. permission revoked mid-scan -- keep the previous list rather than crash.
                Log.w(TAG, "Library scan failed", e)
            } finally {
                // A newer refresh() may have replaced this one; only the latest clears the flag.
                if (generation == scanGeneration) _scanning.value = false
            }
        }
    }

    /** Re-checks the music permission (it can change in system settings while the app is in the background). */
    fun onAppResumed() {
        if (checkAudioPermission() != _hasAudioPermission.value) refresh()
    }

    fun setIncludePhoneLibrary(include: Boolean) {
        store.update { it.copy(includePhoneLibrary = include) }
        refresh()
    }

    /** Returns false if that folder was already added. */
    fun addFolder(treeUri: Uri): Boolean {
        val key = treeUri.toString()
        if (key in store.settings.value.folders) return false
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't persist access to $treeUri", e)
        }
        store.update { it.copy(folders = it.folders + key) }
        refresh()
        return true
    }

    fun removeFolder(treeUriString: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUriString), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Already gone -- nothing to release.
        }
        store.update { settings -> settings.copy(folders = settings.folders.filterNot { it == treeUriString }) }
        refresh()
    }

    fun trackFor(uri: String): Track? = tracksByUri[uri]

    // --- Playlists -------------------------------------------------------

    /** Returns the new playlist's id. */
    fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        store.update { it.copy(playlists = it.playlists + Playlist(id, name.trim(), emptyList())) }
        return id
    }

    fun addToPlaylist(id: String, uris: List<String>) =
        updatePlaylist(id) { it.copy(trackUris = it.trackUris + uris) }

    /** By position, not by URI -- a track added twice only loses the one copy. */
    fun removeFromPlaylist(id: String, index: Int) = updatePlaylist(id) { playlist ->
        if (index !in playlist.trackUris.indices) playlist
        else playlist.copy(trackUris = playlist.trackUris.filterIndexed { i, _ -> i != index })
    }

    fun deletePlaylist(id: String) =
        store.update { settings -> settings.copy(playlists = settings.playlists.filterNot { it.id == id }) }

    private fun updatePlaylist(id: String, transform: (Playlist) -> Playlist) =
        store.update { settings ->
            settings.copy(playlists = settings.playlists.map { if (it.id == id) transform(it) else it })
        }

    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LibraryRepository"
    }
}
