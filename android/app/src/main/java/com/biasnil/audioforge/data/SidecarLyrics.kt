package com.biasnil.audioforge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit

/**
 * The .lrc file next to a song (same name, .lrc), kept in step when the
 * song is renamed or retitled. Android only lets the app reach it when:
 * it was exported from the song editor (remembered here); the song is in
 * an added folder; or the user gave access to the song's folder once
 * ([grantFolder]).
 */
class SidecarLyrics(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    /** Song URI -> the .lrc exported for it. */
    private val exported = appContext.getSharedPreferences("lrc_files", Context.MODE_PRIVATE)

    /** False after "Don't ask again": no more asking for a song's folder to update its .lrc. */
    var mayAsk: Boolean
        get() = exported.getBoolean(MAY_ASK, true)
        set(value) = exported.edit { putBoolean(MAY_ASK, value) }

    /** Remembers the .lrc just exported for [trackUri], so renames and retitles can follow it. */
    fun rememberExport(trackUri: String, lrc: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(lrc, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        exported.edit { putString(trackUri, lrc.toString()) }
    }

    /** The song's .lrc if the app can reach it and it exists; null otherwise. */
    fun find(track: Track): Uri? {
        exported.getString(track.uri, null)?.let(Uri::parse)?.takeIf(::exists)?.let { return it }
        track.sidecarLyricsUri?.let(Uri::parse)?.takeIf { it.lastPathSegment.orEmpty().endsWith(".lrc", ignoreCase = true) && exists(it) }?.let { return it }
        val docId = lrcDocumentId(track) ?: return null
        for (tree in grantedTrees()) {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            if (docId.startsWith("$treeId/") || docId.substringBeforeLast('/') == treeId) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                if (exists(uri)) return uri
            }
        }
        return null
    }

    /** Whether asking for the song's folder could reach a .lrc the app can't see now (phone-library songs). */
    fun couldAskForFolder(track: Track): Boolean {
        val docId = lrcDocumentId(track) ?: return false
        if (exported.contains(track.uri)) return false
        val folderId = docId.substringBeforeLast('/')
        return grantedTrees().none { tree ->
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            folderId == treeId || folderId.startsWith("$treeId/")
        }
    }

    /** Where Android's folder picker starts: the song's folder. */
    fun folderOf(track: Track): Uri? = lrcDocumentId(track)?.let {
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, it.substringBeforeLast('/'))
    }

    /** Keeps access to a folder the user picked (for this and later songs in it). */
    fun grantFolder(tree: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "Couldn't keep access to $tree", it) }
    }

    /**
     * Brings [lrc] in line with the song: new [title] / [artist] in its
     * [ti:] / [ar:] lines (when given), and renamed to [newBaseName].lrc
     * (when given). Returns the .lrc's URI afterwards (a rename can change
     * it), or null if it couldn't be changed.
     */
    fun update(track: Track, lrc: Uri, title: String?, artist: String?, newBaseName: String?): Uri? = try {
        if (title != null || artist != null) {
            val text = resolver.openInputStream(lrc)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val updated = withLrcTags(text, title, artist)
            if (updated != text) resolver.openOutputStream(lrc, "wt")!!.use { it.write(updated.toByteArray(Charsets.UTF_8)) }
        }
        var result = lrc
        if (newBaseName != null) {
            result = DocumentsContract.renameDocument(resolver, lrc, "$newBaseName.lrc") ?: lrc
            if (exported.getString(track.uri, null) == lrc.toString()) exported.edit { putString(track.uri, result.toString()) }
        }
        result
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't update $lrc", e)
        null
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun grantedTrees(): List<Uri> =
        resolver.persistedUriPermissions.filter { it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }

    /** "primary:Music/Rock/Song.lrc" for a song on the phone's own storage; null for others. */
    private fun lrcDocumentId(track: Track): String? {
        if (track.folderKey.substringBefore('|') != "external_primary") return null
        val base = track.fileName.substringBeforeLast('.').ifEmpty { return null }
        return "primary:" + track.folderKey.substringAfter('|') + base + ".lrc"
    }

    private companion object {
        const val TAG = "SidecarLyrics"
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val MAY_ASK = "__may_ask"
    }
}

/**
 * [text] with its [ti:] / [ar:] lines set to [title] / [artist] (null =
 * leave it). A tag the file doesn't have yet is added at the top.
 */
fun withLrcTags(text: String, title: String?, artist: String?): String {
    var result = text
    for ((tag, value) in listOf("ti" to title, "ar" to artist)) {
        if (value == null) continue
        val line = Regex("""(?im)^\[$tag:[^\]\r\n]*]""")
        result = if (line.containsMatchIn(result)) line.replace(result) { "[$tag:${value.replace("]", "")}]" }
        else "[$tag:${value.replace("]", "")}]\n$result"
    }
    return result
}
