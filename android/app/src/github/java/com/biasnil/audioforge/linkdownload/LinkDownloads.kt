package com.biasnil.audioforge.linkdownload

import android.content.Context
import android.util.Log
import com.biasnil.audioforge.data.LibraryRepository
import com.biasnil.audioforge.data.LinkTarget
import com.biasnil.audioforge.data.safeFileName
import com.biasnil.audioforge.data.saveToPhoneLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A download from a link, while it runs and after it ends. */
data class LinkJob(
    val target: LinkTarget,
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    /** The item downloading now, and how far along it is (0..1). */
    val current: String? = null,
    val progress: Float = 0f,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Downloads from links, one item at a time (polite to the sites, and simple),
 * into the phone's Music folder (songs) or Audiobooks folder (one file per
 * book, or a playlist's parts in a folder of their own, as one book). Runs
 * under [LinkDownloadService], so it carries on with the screen off.
 */
object LinkDownloads {
    private const val TAG = "LinkDownloads"

    private val _job = MutableStateFlow<LinkJob?>(null)
    /** The current download, or the last one's result until [dismiss]. */
    val job: StateFlow<LinkJob?> = _job.asStateFlow()
    private var running: Job? = null

    val isRunning: Boolean get() = running?.isActive == true

    /** Looks up what's behind [url] (blocks; Dispatchers.IO). */
    suspend fun look(context: Context, url: String): LinkInfo = withContext(Dispatchers.IO) {
        YtDlp.init(context)
        YtDlp.updateOnce(context)
        YtDlp.list(url.trim())
    }

    /**
     * Downloads [entries] of [info]. For an audiobook playlist, [asOneBook]
     * files the parts as one book named after the playlist. [tags] (by
     * [LinkEntry.key]) are the reviewed artist and title of songs.
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        library: LibraryRepository,
        info: LinkInfo,
        entries: List<LinkEntry>,
        target: LinkTarget,
        asOneBook: Boolean,
        tags: Map<String, SongTags> = emptyMap(),
    ) {
        if (isRunning || entries.isEmpty()) return
        val appContext = context.applicationContext
        _job.value = LinkJob(target, entries.size)
        running = scope.launch {
            val workDir = File(appContext.cacheDir, "link-download")
            val book = if (target == LinkTarget.Audiobooks && asOneBook) info.playlistTitle else null
            try {
                for (entry in entries) {
                    val songTags = if (target == LinkTarget.Songs) tags[entry.key] else null
                    _job.update { it?.copy(current = songTags?.title ?: entry.title, progress = 0f) }
                    val saved = try {
                        withContext(Dispatchers.IO) {
                            YtDlp.init(appContext)
                            val file = YtDlp.download(info, entry, target == LinkTarget.Audiobooks, book, workDir, songTags) { progress, _ ->
                                _job.update { it?.copy(progress = progress.coerceIn(0f, 1f)) }
                            }
                            save(appContext, file, target, book, entry, songTags)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't download ${entry.url}", e)
                        false
                    }
                    _job.update { it?.copy(done = it.done + if (saved) 1 else 0, failed = it.failed + if (saved) 0 else 1) }
                    if (saved) library.refresh()
                }
                _job.update { it?.copy(current = null, finished = true) }
            } catch (e: CancellationException) {
                _job.update { it?.copy(current = null, finished = true, cancelled = true) }
                throw e
            } finally {
                workDir.deleteRecursively()
                LinkDownloadService.stop(appContext)
            }
        }
        LinkDownloadService.start(appContext)
    }

    fun cancel() {
        YtDlp.cancel()
        running?.cancel()
    }

    /** Clears a finished download's result. */
    fun dismiss() {
        if (!isRunning) _job.value = null
    }

    /** Copies a finished file into Music/ or Audiobooks/ (a book from a playlist gets its own folder). */
    private fun save(context: Context, file: File, target: LinkTarget, book: String?, entry: LinkEntry, tags: SongTags?): Boolean {
        val audiobook = target == LinkTarget.Audiobooks
        val folder = when {
            !audiobook -> "Music/"
            book != null -> "Audiobooks/" + (safeFileName(book).take(100).ifEmpty { "Audiobook" }) + "/"
            else -> "Audiobooks/"
        }
        // Named like its tags when they were reviewed: "Artist - Title".
        val tagged = tags?.let { safeFileName(listOf(it.artist, it.title).filter(String::isNotBlank).joinToString(" - ")).take(150) }
        val base = tagged?.ifEmpty { null } ?: file.nameWithoutExtension.ifEmpty { safeFileName(entry.title) }
        // A playlist book's parts are numbered, so they sort in order whatever their titles.
        val name = if (book != null && entry.playlistIndex != null) "%03d %s".format(entry.playlistIndex, base) else base
        // .m4b tells other players it's an audiobook (with chapters); it's the same file as .m4a.
        val ext = if (audiobook) "m4b" else "m4a"
        return saveToPhoneLibrary(context, file, folder, "$name.$ext", "audio/mp4") != null
    }
}
