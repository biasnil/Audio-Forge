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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/**
 * Songs the user marked "Not duplicates", as pairs ([pairKey]), saved to
 * files/duplicate_dismissals.json a moment after each change. Pairs rather
 * than groups: when a new copy turns up, it's compared again, and only the
 * pairs the user dismissed stay dismissed.
 */
class DuplicateDismissals(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    private val _pairs = MutableStateFlow(read())
    val pairs: StateFlow<Set<String>> = _pairs.asStateFlow()

    /** Drops pairs whose songs are no longer in the library, once it has been scanned. */
    fun pruneWhenLoaded(library: StateFlow<List<Track>>) {
        scope.launch {
            val tracks = library.first { it.isNotEmpty() }
            val kept = prunePairs(_pairs.value, tracks.mapTo(HashSet()) { it.uri })
            if (kept.size != _pairs.value.size) set(kept)
        }
    }

    fun dismiss(pairs: Set<String>) = set(_pairs.value + pairs)

    fun restore(pairs: Set<String>) = set(_pairs.value - pairs)

    /** Replaces the whole set (Undo puts back what was there before). */
    fun set(pairs: Set<String>) {
        _pairs.value = pairs
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            val snapshot = _pairs.value
            launch(Dispatchers.IO) { writeLock.withLock { write(snapshot) } }
        }
    }

    private fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNullTo(HashSet()) { array.optString(it, null) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            emptySet()
        }
    }

    private fun write(pairs: Set<String>) {
        try {
            // Write-then-rename so a crash mid-write never leaves a truncated file.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(JSONArray(pairs.sorted()).toString())
            if (!temp.renameTo(file)) Log.w(TAG, "Couldn't replace $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private companion object {
        const val TAG = "DuplicateDismissals"
        const val FILE_NAME = "duplicate_dismissals.json"
    }
}

/** The pairs in [pairs] whose two songs are both still in [libraryUris]. */
fun prunePairs(pairs: Set<String>, libraryUris: Set<String>): Set<String> =
    pairs.filterTo(HashSet()) { pair ->
        val (a, b) = pair.split('\u0000').let { it.getOrNull(0) to it.getOrNull(1) }
        a in libraryUris && b in libraryUris
    }
