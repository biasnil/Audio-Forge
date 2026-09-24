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

    /** Every phone-library folder with songs, hidden ones included (so they can be shown again). */
    private val _phoneFolders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val phoneFolders: StateFlow<List<LibraryFolder>> = _phoneFolders.asStateFlow()

    // The last scan, before hiding folders -- so hiding/showing one is instant, no rescan.
    private var phoneTracks: List<Track> = emptyList()
    private var addedFolderTracks: List<Track> = emptyList()

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
                val (phone, folders) = withContext(Dispatchers.IO) {
                    val phone = if (settings.includePhoneLibrary && hasPermission) mediaStore.queryMusic() else emptyList()
                    phone to settings.folders.flatMap { folderSource.scan(it) }
                }
                phoneTracks = phone
                addedFolderTracks = folders
                publish()
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
        persistFolderAccess(treeUri)
        store.update { it.copy(folders = it.folders + key) }
        refresh()
        return true
    }

    fun removeFolder(treeUriString: String) {
        val uri = Uri.parse(treeUriString)
        for (flags in listOf(READ_WRITE, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
                break
            } catch (e: SecurityException) {
                // Not held with these flags (or already gone) -- try read-only / nothing to release.
            }
        }
        store.update { settings -> settings.copy(folders = settings.folders.filterNot { it == treeUriString }) }
        refresh()
    }

    fun trackFor(uri: String): Track? = tracksByUri[uri]

    /** Hides (or shows again) a phone-library folder's songs, subfolders included. */
    fun setFolderHidden(folderKey: String, hidden: Boolean) {
        if (folderKey.isEmpty()) return
        store.update { settings ->
            settings.copy(hiddenFolders = if (hidden) settings.hiddenFolders + folderKey else settings.hiddenFolders - folderKey)
        }
        publish()
    }

    /** The library as shown: phone tracks minus hidden folders, then added folders, de-duplicated. */
    private fun publish() {
        val merged = mergeSources(excludeFolders(phoneTracks, store.settings.value.hiddenFolders), addedFolderTracks)
        tracksByUri = merged.associateBy { it.uri }
        _tracks.value = merged
        _phoneFolders.value = folderCounts(phoneTracks)
    }

    /** Whether tag edits can be saved into files of this added folder (folders added before tag editing existed are read-only). */
    fun hasFolderWriteAccess(treeUriString: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == treeUriString && it.isWritePermission }

    /**
     * After the user re-picks an added folder in the system picker to allow
     * editing: keeps read + write access. False if they picked a different folder.
     */
    fun grantFolderWriteAccess(picked: Uri, treeUriString: String): Boolean {
        if (picked.toString() != treeUriString) return false
        persistFolderAccess(picked)
        return hasFolderWriteAccess(treeUriString)
    }

    /** Re-reads one track's tags from its file (after an edit) and updates it everywhere in the library. */
    suspend fun reloadTrack(track: Track): Track {
        val updated = try {
            withContext(Dispatchers.IO) { readTags(context, track) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't re-read ${track.fileName}", e)
            track
        }
        val replace: (Track) -> Track = { if (it.uri == updated.uri) updated else it }
        phoneTracks = phoneTracks.map(replace)
        addedFolderTracks = addedFolderTracks.map(replace)
        tracksByUri = tracksByUri + (updated.uri to updated)
        _tracks.value = _tracks.value.map(replace)
        return updated
    }

    /** Read + write if the picker granted it (it normally does), else read-only. */
    private fun persistFolderAccess(treeUri: Uri) {
        for (flags in listOf(READ_WRITE, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Couldn't persist access ($flags) to $treeUri", e)
            }
        }
    }

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
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
