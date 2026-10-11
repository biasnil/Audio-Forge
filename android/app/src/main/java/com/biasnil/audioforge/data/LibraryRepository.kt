package com.biasnil.audioforge.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
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

    fun renamePlaylist(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) updatePlaylist(id) { it.copy(name = trimmed) }
    }

    /** Replaces the playlist's contents (after reordering). */
    fun setPlaylistUris(id: String, uris: List<String>) = updatePlaylist(id) { it.copy(trackUris = uris) }

    /** Adds songs to the end, skipping any already in it (the iOS app's Add to Playlist). */
    fun addToPlaylistUnique(id: String, uris: List<String>) = updatePlaylist(id) { playlist ->
        val have = playlist.trackUris.toHashSet()
        playlist.copy(trackUris = playlist.trackUris + uris.filter { have.add(it) })
    }

    // --- Playlist covers ---------------------------------------------------

    fun coverFile(fileName: String): File = File(coverFolder(), fileName)

    /** Uses a picked image as the playlist's cover. */
    suspend fun setPlaylistCover(id: String, bytes: ByteArray, mimeType: String) {
        val extension = if (mimeType == "image/png") "png" else "jpg"
        val name = "$id-${System.currentTimeMillis()}.$extension"
        withContext(Dispatchers.IO) { File(coverFolder(), name).writeBytes(bytes) }
        val old = store.settings.value.playlists.firstOrNull { it.id == id }?.coverFile
        updatePlaylist(id) { it.copy(coverFile = name) }
        old?.let { withContext(Dispatchers.IO) { coverFile(it).delete() } }
    }

    /** Back to the grid of album covers. */
    fun removePlaylistCover(id: String) {
        val old = store.settings.value.playlists.firstOrNull { it.id == id }?.coverFile ?: return
        updatePlaylist(id) { it.copy(coverFile = null) }
        coverFile(old).delete()
    }

    private fun coverFolder(): File = File(context.filesDir, "PlaylistCovers").apply { mkdirs() }

    // --- Smart playlists -----------------------------------------------------

    /** Adds a new smart playlist or replaces the one with the same id. */
    fun saveSmartPlaylist(playlist: SmartPlaylist) = store.update { settings ->
        val exists = settings.smartPlaylists.any { it.id == playlist.id }
        settings.copy(
            smartPlaylists = if (exists) settings.smartPlaylists.map { if (it.id == playlist.id) playlist else it }
            else settings.smartPlaylists + playlist
        )
    }

    fun deleteSmartPlaylist(id: String) =
        store.update { settings -> settings.copy(smartPlaylists = settings.smartPlaylists.filterNot { it.id == id }) }

    // --- .m3u -------------------------------------------------------------------

    /**
     * Creates a playlist from an .m3u / .m3u8 file, matching its entries to
     * library songs. Returns (playlist id, matched, total entries), or null
     * if the file has no entries.
     */
    suspend fun importM3u(uri: Uri): Triple<String, Int, Int>? {
        val (name, text) = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            displayName(uri).substringBeforeLast('.') to decodeText(bytes)
        } ?: return null
        val entries = parseM3u(text)
        if (entries.isEmpty()) return null
        val matched = matchM3u(entries, _tracks.value, ::trackPath)
        val id = createPlaylist(name.ifBlank { "Imported Playlist" })
        addToPlaylist(id, matched.map { it.uri })
        return Triple(id, matched.size, entries.size)
    }

    /** Writes a playlist as .m3u8 to [destination] (from the system "save as" picker). */
    suspend fun exportM3u(tracks: List<Track>, destination: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "wt")?.use { out ->
            out.write(writeM3u(tracks, ::trackPath).toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("Couldn't open $destination")
    }

    /**
     * A track's path inside its storage, as other apps (and AudioForge on the
     * desktop and iOS) would write it in a playlist: "Music/Rock/a.mp3".
     */
    fun trackPath(track: Track): String {
        if (track.folderKey.isNotEmpty()) return folderPath(track.folderKey) + "/" + track.fileName
        return try {
            DocumentsContract.getDocumentId(Uri.parse(track.uri)).substringAfter(':')
        } catch (e: IllegalArgumentException) {
            track.fileName
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    /** UTF-8 (with or without BOM), else Latin-1 -- old .m3u files are often the latter. */
    private fun decodeText(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    // --- Importing and deleting songs -----------------------------------------------

    /**
     * Copies picked audio files into Music/AudioForge on the phone, where the
     * phone's music library picks them up. Returns how many were copied.
     */
    suspend fun importFiles(uris: List<Uri>): Int {
        var copied = 0
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            for (source in uris) {
                try {
                    val name = displayName(source).ifEmpty { "Imported ${System.currentTimeMillis()}" }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, resolver.getType(source) ?: "audio/mpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/AudioForge")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val target = resolver.insert(collection, values) ?: continue
                    try {
                        resolver.openInputStream(source)?.use { input ->
                            resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
                        } ?: throw java.io.IOException("Couldn't read $source")
                        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                        copied++
                    } catch (e: Exception) {
                        resolver.delete(target, null, null) // don't leave a half-written file
                        throw e
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't import $source", e)
                }
            }
        }
        if (copied > 0) refresh()
        return copied
    }

    /**
     * Deletes songs' files. Files in added folders are deleted straight away;
     * phone-library files need the system's confirmation, so for those this
     * returns the request to show (then call [refresh] once it's answered).
     */
    suspend fun deleteTracks(tracks: List<Track>): IntentSender? {
        val phone = tracks.filter { it.sourceFolder == null }.map { Uri.parse(it.uri) }
        val documents = tracks.filter { it.sourceFolder != null }
        var removedAny = false
        withContext(Dispatchers.IO) {
            for (track in documents) {
                try {
                    if (DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(track.uri))) removedAny = true
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't delete ${track.fileName}", e)
                }
            }
        }
        if (removedAny) refresh()
        if (phone.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, phone).intentSender
    }

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
