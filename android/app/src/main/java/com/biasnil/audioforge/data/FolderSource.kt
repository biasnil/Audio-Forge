package com.biasnil.audioforge.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * Folders the user added (the desktop's "Add Folder..."), read through the
 * Storage Access Framework with a persisted tree permission. Each audio
 * file's tags are read with MediaMetadataRetriever, so this is slower than
 * MediaStore -- it always runs in the background.
 */
class FolderSource(private val context: Context) {

    private val resolver = context.contentResolver

    private class Entry(val id: String, val name: String, val size: Long)

    suspend fun scan(treeUriString: String): List<Track> {
        val treeUri = Uri.parse(treeUriString)
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        val tracks = ArrayList<Track>()
        scanDirectory(treeUri, treeUriString, rootId, tracks)
        return tracks
    }

    private suspend fun scanDirectory(treeUri: Uri, treeKey: String, directoryId: String, out: MutableList<Track>) {
        val subdirectories = ArrayList<String>()
        val audioFiles = ArrayList<Entry>()
        var coverUri: String? = null
        var coverRank = Int.MAX_VALUE

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        try {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> subdirectories += id
                        isAudio(name, mime) -> audioFiles += Entry(id, name, size)
                        else -> {
                            // Same fallback names, in the same priority, as the desktop app.
                            val rank = COVER_NAMES.indexOf(name.lowercase(Locale.ROOT))
                            if (rank in 0 until coverRank) {
                                coverRank = rank
                                coverUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Permission revoked, folder deleted, provider error...: skip it.
            Log.w(TAG, "Couldn't list $children", e)
            return
        }

        for (entry in audioFiles) {
            currentCoroutineContext().ensureActive()
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
            out += readTrack(documentUri, entry, treeKey, coverUri)
        }
        for (subdirectory in subdirectories) {
            scanDirectory(treeUri, treeKey, subdirectory, out)
        }
    }

    private fun readTrack(uri: Uri, entry: Entry, treeKey: String, coverUri: String?): Track {
        val base = Track(
            uri = uri.toString(),
            title = entry.name.substringBeforeLast('.'),
            fileName = entry.name,
            sizeBytes = entry.size,
            sourceFolder = treeKey,
            folderCoverUri = coverUri,
        )
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
                base.copy(
                    title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { base.title },
                    artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                    trackNumber = discTrack(
                        tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                        tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    ),
                    durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                    bitrateKbps = ((tag(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0) / 1000).toInt(),
                    sampleRateHz = tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0,
                )
            }
        } catch (e: Exception) {
            // Unreadable tags: still list the file, by name.
            Log.w(TAG, "Couldn't read tags of ${entry.name}", e)
            base
        }
    }

    private companion object {
        const val TAG = "FolderSource"

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")

        // Playlists report audio/* MIME types but aren't playable audio.
        val PLAYLIST_MIME_TYPES = setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls")

        val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "album.jpg", "album.jpeg", "album.png",
            "front.jpg", "front.jpeg", "front.png",
        )

        fun isAudio(name: String, mime: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS ||
                (mime.startsWith("audio/") && mime !in PLAYLIST_MIME_TYPES)

        /** "1/2" + "3/12" -> 1003, MediaStore's disc * 1000 + track encoding. */
        fun discTrack(disc: String, track: String): Int {
            val trackNumber = track.substringBefore('/').trim().toIntOrNull() ?: return 0
            val discNumber = disc.substringBefore('/').trim().toIntOrNull() ?: 0
            return discNumber * 1000 + trackNumber
        }
    }
}
