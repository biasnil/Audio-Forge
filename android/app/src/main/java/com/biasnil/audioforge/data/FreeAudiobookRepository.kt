package com.biasnil.audioforge.data

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.biasnil.audioforge.tags.TagFileEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a book's download is going, in parts (files). */
data class FreeBookDownload(val done: Int, val failed: Int, val total: Int) {
    val running: Boolean get() = done + failed < total
}

/**
 * Free audiobooks (see FreeAudiobooks.kt): search, a book's page,
 * and downloading it. Android's download manager fetches each part into
 * the app's own folder (it carries on if the app closes, with its own
 * progress notification); then each part gets full tags (title, book,
 * author, part number, cover) and moves into the phone's Audiobooks
 * folder, "Audiobooks/Author - Title/", where the Audiobooks tab finds the
 * parts as one book. Parts finished while the app was closed are moved
 * the next time it starts.
 */
class FreeAudiobookRepository(
    context: Context,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val jobsFile = File(appContext.filesDir, JOBS_FILE)
    /** Changed only while holding this repository's lock (the main thread and the polling both change it). */
    @Volatile private var jobs: List<BookJob> = readJobs()
    private var pollJob: Job? = null

    private val _downloads = MutableStateFlow(progressOf(jobs))
    /** By book id; books downloading now, or with parts that failed. */
    val downloads: StateFlow<Map<String, FreeBookDownload>> = _downloads.asStateFlow()

    init {
        if (jobs.isNotEmpty()) poll()
    }

    /** The last search and its results, so going back from a book shows them again. */
    var lastSearch: Pair<String, List<FreeBook>>? = null
        private set

    /** Sends the search words to the Internet Archive. */
    suspend fun search(term: String): List<FreeBook> = withContext(Dispatchers.IO) {
        parseFreeBookSearch(PodcastRepository.get(freeBookSearchUrl(term)) { it.bufferedReader().readText() })
    }.also { lastSearch = term to it }

    suspend fun details(id: String): FreeBookDetails = withContext(Dispatchers.IO) {
        parseFreeBookDetails(id, PodcastRepository.get(freeBookDetailsUrl(id)) { it.bufferedReader().readText() })
    }

    /** Downloads every part of the book (again, if it was before). False if it couldn't start. */
    suspend fun download(details: FreeBookDetails): Boolean {
        if (details.parts.isEmpty()) return false
        val book = details.book
        val dir = bookDir(book.id)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            // The cover is put into every part, so it's fetched once, first.
            details.coverFileName?.let { name ->
                runCatching { File(dir, COVER_FILE).writeBytes(PodcastRepository.get(freeBookFileUrl(book.id, name)) { it.readBytes() }) }
                    .onFailure { Log.w(TAG, "No cover for ${book.id}", it) }
            }
        }
        val parts = try {
            details.parts.mapIndexed { index, part ->
                BookPart(index, part.title, part.fileName, enqueue(book, index, part), PartState.Queued)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start downloading ${book.id}", e)
            return false
        }
        synchronized(this) {
            cancelDownloads(jobs.firstOrNull { it.id == book.id })
            setJobs(jobs.filterNot { it.id == book.id } + BookJob(book.id, book.title, book.author, freeBookFolder(book), parts))
        }
        poll()
        return true
    }

    /** Tries the parts that failed again. */
    fun retry(id: String) {
        synchronized(this) {
            val job = jobs.firstOrNull { it.id == id } ?: return
            val book = FreeBook(job.id, job.title, job.author)
            val parts = job.parts.map { part ->
                if (part.state != PartState.Failed) part
                else runCatching { part.copy(downloadId = enqueue(book, part.index, FreeBookPart(part.fileName, part.title, 0)), state = PartState.Queued) }
                    .getOrDefault(part)
            }
            setJobs(jobs.map { if (it.id == id) it.copy(parts = parts) else it })
        }
        poll()
    }

    /** Stops the book's download; parts already in Audiobooks stay. */
    fun cancel(id: String) {
        synchronized(this) {
            val job = jobs.firstOrNull { it.id == id } ?: return
            cancelDownloads(job)
            setJobs(jobs - job)
        }
        bookDir(id).deleteRecursively()
    }

    private fun enqueue(book: FreeBook, index: Int, part: FreeBookPart): Long {
        val request = DownloadManager.Request(freeBookFileUrl(book.id, part.fileName).toUri())
            .setTitle(part.title)
            .setDescription(book.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appContext, null, "$DOWNLOAD_DIR/${dirName(book.id)}/${partTempName(index)}")
            .addRequestHeader("User-Agent", UpdateChecker.USER_AGENT)
        return downloadManager.enqueue(request)
    }

    private fun cancelDownloads(job: BookJob?) {
        val ids = job?.parts?.filter { it.state == PartState.Queued }?.map { it.downloadId }.orEmpty()
        if (ids.isNotEmpty()) runCatching { downloadManager.remove(*ids.toLongArray()) }
    }

    /** Checks the downloads every few seconds while any part is still coming, and moves finished parts. */
    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (jobs.any { job -> job.parts.any { it.state == PartState.Queued } }) {
                val moved = withContext(Dispatchers.IO) { checkDownloads() }
                if (moved) library.refresh()
                delay(POLL_MS)
            }
        }
    }

    /** One look at the download manager; true if any part was moved into Audiobooks. */
    private fun checkDownloads(): Boolean {
        val queued = jobs.flatMap { job -> job.parts.filter { it.state == PartState.Queued }.map { job to it } }
        if (queued.isEmpty()) return false
        val statuses = HashMap<Long, Int>()
        downloadManager.query(DownloadManager.Query().setFilterById(*queued.map { it.second.downloadId }.toLongArray()))?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            while (cursor.moveToNext()) statuses[cursor.getLong(idColumn)] = cursor.getInt(statusColumn)
        }
        var moved = false
        val updates = HashMap<Pair<String, Int>, PartState>()
        for ((job, part) in queued) {
            when (statuses[part.downloadId]) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val ok = moveIntoAudiobooks(job, part)
                    updates[job.id to part.index] = if (ok) PartState.Done else PartState.Failed
                    moved = moved || ok
                }
                // Failed, or gone from the download manager's list (cleared by the user).
                DownloadManager.STATUS_FAILED, null -> updates[job.id to part.index] = PartState.Failed
            }
        }
        if (updates.isNotEmpty()) synchronized(this) {
            val next = jobs.map { job ->
                job.copy(parts = job.parts.map { part -> updates[job.id to part.index]?.let { part.copy(state = it) } ?: part })
            }
            // A finished book (every part in Audiobooks) leaves the list; one with failed parts stays for Try again.
            val finished = next.filter { job -> job.parts.all { it.state == PartState.Done } }
            finished.forEach { bookDir(it.id).deleteRecursively() }
            setJobs(next - finished.toSet())
        }
        return moved
    }

    /** Tags a downloaded part and copies it into Audiobooks; its download is then cleared. */
    private fun moveIntoAudiobooks(job: BookJob, part: BookPart): Boolean {
        val file = File(bookDir(job.id), partTempName(part.index))
        if (!file.exists()) return false
        try {
            TagFileEditor.write(
                file,
                original = emptyMap(),
                edited = mapOf(
                    "TITLE" to part.title,
                    "ALBUM" to job.title,
                    "ARTIST" to job.author,
                    "ALBUM_ARTIST" to job.author,
                    "TRACK" to (part.index + 1).toString(),
                    "TRACK_TOTAL" to job.parts.size.toString(),
                    "GENRE" to GENRE,
                ),
            )
            File(bookDir(job.id), COVER_FILE).takeIf { it.exists() }?.let { TagFileEditor.writeCover(file, it.readBytes(), "image/jpeg") }
        } catch (e: Exception) {
            // Untagged is still playable: the Archive's files have basic tags of their own.
            Log.w(TAG, "Couldn't tag part ${part.index + 1} of ${job.id}", e)
        }
        val name = freeBookPartFileName(part.index, FreeBookPart("", part.title, 0))
        val saved = saveToPhoneLibrary(appContext, file, "Audiobooks/${job.folder}/", name, "audio/mpeg") != null
        if (saved) {
            file.delete()
            runCatching { downloadManager.remove(part.downloadId) }
        }
        return saved
    }

    private fun bookDir(id: String) = File(appContext.getExternalFilesDir(null), "$DOWNLOAD_DIR/${dirName(id)}")

    private fun setJobs(next: List<BookJob>) {
        jobs = next
        _downloads.value = progressOf(next)
        runCatching { jobsFile.writeText(JSONArray(next.map { it.toJson() }).toString()) }
            .onFailure { Log.w(TAG, "Couldn't save $JOBS_FILE", it) }
    }

    private fun readJobs(): List<BookJob> = try {
        if (!jobsFile.exists()) emptyList() else {
            val array = JSONArray(jobsFile.readText())
            (0 until array.length()).map { BookJob.fromJson(array.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read $JOBS_FILE", e)
        emptyList()
    }

    private enum class PartState { Queued, Done, Failed }

    private data class BookPart(val index: Int, val title: String, val fileName: String, val downloadId: Long, val state: PartState)

    private data class BookJob(val id: String, val title: String, val author: String, val folder: String, val parts: List<BookPart>) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("author", author).put("folder", folder)
            .put("parts", JSONArray(parts.map {
                JSONObject().put("index", it.index).put("title", it.title).put("file", it.fileName)
                    .put("downloadId", it.downloadId).put("state", it.state.name)
            }))

        companion object {
            fun fromJson(obj: JSONObject): BookJob {
                val parts = obj.getJSONArray("parts")
                return BookJob(
                    id = obj.getString("id"),
                    title = obj.optString("title"),
                    author = obj.optString("author"),
                    folder = obj.getString("folder"),
                    parts = (0 until parts.length()).map { i ->
                        val part = parts.getJSONObject(i)
                        BookPart(
                            part.getInt("index"), part.optString("title"), part.optString("file"),
                            part.getLong("downloadId"), PartState.valueOf(part.getString("state")),
                        )
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "FreeAudiobooks"
        const val JOBS_FILE = "book_downloads.json"
        const val DOWNLOAD_DIR = "book-downloads"
        const val COVER_FILE = "cover.jpg"
        const val GENRE = "Audiobook"
        const val POLL_MS = 2_000L

        fun dirName(id: String) = safeFileName(id).ifEmpty { "book" }
        fun partTempName(index: Int) = "%03d.mp3".format(index + 1)

        fun progressOf(jobs: List<BookJob>): Map<String, FreeBookDownload> = jobs.associate { job ->
            job.id to FreeBookDownload(
                done = job.parts.count { it.state == PartState.Done },
                failed = job.parts.count { it.state == PartState.Failed },
                total = job.parts.size,
            )
        }
    }
}
