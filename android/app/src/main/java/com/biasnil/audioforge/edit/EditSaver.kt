package com.biasnil.audioforge.edit

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.safeFileName
import com.biasnil.audioforge.data.saveToPhoneLibrary
import com.biasnil.audioforge.tags.TagFileEditor
import java.io.File
import java.util.Locale

/**
 * Saving an edited song: as a new copy next to it, or over the original.
 * The original's tags and cover go into the new file, which keeps the
 * song's format: M4A, MP3 (jump3r), FLAC and WAV. Other formats (OGG,
 * Opus...) are saved as an M4A copy and can't be replaced.
 */
object EditSaver {
    private const val TAG = "EditSaver"

    /** What the edited [track] is written as: its own format where possible, else M4A. */
    fun formatFor(track: Track): EditFormat = when (extensionOf(track)) {
        "wav" -> EditFormat.Wav
        "mp3" -> EditFormat.Mp3
        "flac" -> EditFormat.Flac
        else -> EditFormat.M4a
    }

    /** Whether the original file can be replaced (the format stays the same). */
    fun canReplace(track: Track): Boolean = extensionOf(track) in setOf("m4a", "mp4", "aac", "wav", "mp3", "flac")

    /** Writes the edited audio to a temp file in [workDir], with the original's tags and cover. */
    suspend fun renderTagged(
        context: Context,
        track: Track,
        source: PcmSource,
        edit: AudioEdit,
        workDir: File,
        onProgress: (Float) -> Unit,
    ): File {
        workDir.mkdirs()
        val format = formatFor(track)
        val out = File(workDir, "edited.${format.extension}")
        EditRenderer.render(source, edit, format, out, Mp3Encoder.bitrateFor(track.bitrateKbps), onProgress)
        copyTags(context, track, out, workDir)
        return out
    }

    /**
     * Saves [rendered] as "<name> (edited)" in the same folder as [track]
     * (the phone's Music folder if that folder can't take it). Returns the
     * folder it went into, or null if it couldn't be saved.
     */
    fun saveCopy(context: Context, track: Track, rendered: File): String? {
        val format = formatFor(track)
        val base = track.fileName.substringBeforeLast('.').ifEmpty { safeFileName(track.title) }.ifEmpty { "Song" }
        val name = "$base (edited).${format.extension}"
        // Added folder: next to the file, if the app may write there.
        track.sourceFolder?.let { tree ->
            runCatching {
                val treeUri = Uri.parse(tree)
                val docId = DocumentsContract.getDocumentId(Uri.parse(track.uri))
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId.substringBeforeLast('/', docId))
                val created = DocumentsContract.createDocument(context.contentResolver, parent, format.mimeType, name)!!
                context.contentResolver.openOutputStream(created)!!.use { out -> rendered.inputStream().use { it.copyTo(out) } }
                return docId.substringBeforeLast('/').substringAfter(':')
            }.onFailure { Log.w(TAG, "Couldn't save next to ${track.uri}; saving in Music", it) }
        }
        // Phone library: the same folder (on the phone's own storage), if Android allows audio there; else Music.
        val folder = if (track.folderKey.substringBefore('|') == "external_primary") track.folderKey.substringAfter('|') else ""
        for (relativePath in listOf(folder, "Music/").filter { it.isNotEmpty() }.distinct()) {
            if (runCatching { saveToPhoneLibrary(context, rendered, relativePath, name, format.mimeType) }.getOrNull() != null) {
                return relativePath.trimEnd('/')
            }
        }
        return null
    }

    /**
     * Puts [rendered] in place of [track]'s file. The original is copied
     * aside first and put back if writing fails, so a failed save never
     * leaves a broken song; the copy is deleted once the edit is in. The
     * song stays the same file to Android, so its playlists, play counts
     * and .lrc stay with it. The caller has Android's permission to change it.
     */
    fun replace(context: Context, track: Track, rendered: File) {
        val uri = Uri.parse(track.uri)
        val resolver = context.contentResolver
        val backup = File(rendered.parentFile, "original-backup")
        resolver.openInputStream(uri)!!.use { input -> backup.outputStream().use { input.copyTo(it) } }
        try {
            resolver.openOutputStream(uri, "wt")!!.use { out -> rendered.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { resolver.openOutputStream(uri, "wt")!!.use { out -> backup.inputStream().use { it.copyTo(out) } } }
                .onFailure { Log.e(TAG, "Couldn't put the original of ${track.uri} back", it) }
            throw e
        } finally {
            backup.delete()
        }
    }

    /** The original's tags and cover, into [out] (best effort: an untagged file still plays). */
    private fun copyTags(context: Context, track: Track, out: File, workDir: File) {
        val original = File(workDir, "original." + extensionOf(track).ifEmpty { "mp3" })
        try {
            context.contentResolver.openInputStream(Uri.parse(track.uri))!!.use { input ->
                original.outputStream().use { input.copyTo(it) }
            }
            val fields = TagFileEditor.read(original).values.filterValues { it.isNotBlank() }
            TagFileEditor.write(out, emptyMap(), fields)
            TagFileEditor.readCover(original)?.let { cover ->
                val mime = if (cover.size > 3 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
                TagFileEditor.writeCover(out, cover, mime)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't copy the tags of ${track.uri}", e)
        } finally {
            original.delete()
        }
    }

    private fun extensionOf(track: Track): String =
        track.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
