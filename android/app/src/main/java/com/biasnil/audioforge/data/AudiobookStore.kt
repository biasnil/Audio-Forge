package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved place in a book: a file ([uri]) and a position in it, with an optional note. */
data class Bookmark(val id: String, val uri: String, val positionMs: Long, val note: String, val createdMs: Long)

/** Everything remembered about audiobooks besides the resume positions (those are in SongData). */
data class AudiobookData(
    val bookmarks: List<Bookmark> = emptyList(),
    /** Book key (see bookKey) -> the file last played, for books split into many files. */
    val lastPart: Map<String, String> = emptyMap(),
)

/**
 * Bookmarks and each book's last-played file, saved to files/audiobooks.json
 * a moment after each change (the same pattern as the other stores).
 */
class AudiobookStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    private val _data = MutableStateFlow(read())
    val data: StateFlow<AudiobookData> = _data.asStateFlow()

    fun addBookmark(uri: String, positionMs: Long, note: String) = update {
        val now = System.currentTimeMillis()
        it.copy(bookmarks = it.bookmarks + Bookmark("$now-${uri.hashCode()}", uri, positionMs, note.trim(), now))
    }

    fun deleteBookmark(id: String) = update { data -> data.copy(bookmarks = data.bookmarks.filterNot { it.id == id }) }

    /** Called when one of [book]'s files starts playing. */
    fun setLastPart(bookKey: String, uri: String) {
        if (_data.value.lastPart[bookKey] == uri) return
        update { it.copy(lastPart = it.lastPart + (bookKey to uri)) }
    }

    private fun update(transform: (AudiobookData) -> AudiobookData) {
        _data.value = transform(_data.value)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val snapshot = _data.value
            launch(Dispatchers.IO) { writeLock.withLock { write(snapshot) } }
        }
    }

    private fun read(): AudiobookData {
        if (!file.exists()) return AudiobookData()
        return try {
            parseAudiobookData(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            AudiobookData()
        }
    }

    private fun write(data: AudiobookData) {
        try {
            // Write-then-rename so a crash mid-write never leaves a truncated file.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(writeAudiobookData(data))
            if (!temp.renameTo(file)) Log.w(TAG, "Couldn't replace $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private companion object {
        const val TAG = "AudiobookStore"
        const val FILE_NAME = "audiobooks.json"
    }
}

fun parseAudiobookData(text: String): AudiobookData {
    val root = JSONObject(text)
    val bookmarks = root.optJSONArray("bookmarks") ?: JSONArray()
    val lastPart = root.optJSONObject("lastPart") ?: JSONObject()
    return AudiobookData(
        bookmarks = (0 until bookmarks.length()).mapNotNull { i ->
            val obj = bookmarks.optJSONObject(i) ?: return@mapNotNull null
            val uri = obj.optString("uri").ifEmpty { return@mapNotNull null }
            Bookmark(
                id = obj.optString("id").ifEmpty { "$i-${uri.hashCode()}" },
                uri = uri,
                positionMs = obj.optLong("positionMs").coerceAtLeast(0),
                note = obj.optString("note"),
                createdMs = obj.optLong("createdMs"),
            )
        }.distinctBy { it.id },
        lastPart = lastPart.keys().asSequence().associateWith { lastPart.optString(it) }.filterValues { it.isNotEmpty() },
    )
}

fun writeAudiobookData(data: AudiobookData): String =
    JSONObject()
        .put("bookmarks", JSONArray(data.bookmarks.map { mark ->
            JSONObject()
                .put("id", mark.id)
                .put("uri", mark.uri)
                .put("positionMs", mark.positionMs)
                .put("note", mark.note)
                .put("createdMs", mark.createdMs)
        }))
        .put("lastPart", JSONObject(data.lastPart))
        .toString()
