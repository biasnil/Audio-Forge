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
import kotlin.math.abs

/**
 * The model and genre table a result came from. Change it when the model,
 * the class map or [GenreTaxonomy]'s table changes, so every song is
 * detected again once.
 */
const val GENRE_MODEL_ID = "yamnet-1/map-1"

/** A song's detected genre (Settings -> Detect Genres). Not the GENRE tag: that's [Track.genre]. */
data class DetectedGenre(
    /** The file's length when it was analyzed; a different length means a different file. */
    val durationMs: Long,
    val status: Status,
    /** "Electronic"... English data names (they can be written into tags), never translated. */
    val broad: String?,
    /** "House"..., when one stands out within [broad]. */
    val subgenre: String?,
    /** [broad]'s share of all genre evidence, 0..1. */
    val confidence: Double,
    /** The model's top labels and scores, for checking and tuning. */
    val top: List<Pair<String, Float>>,
    val model: String = GENRE_MODEL_ID,
) {
    enum class Status { OK, UNKNOWN, NOT_MUSIC, TOO_SHORT, FAILED }

    /** "Electronic · House", or just "Electronic". */
    val label: String? get() = broad?.let { if (subgenre != null) "$it · $subgenre" else it }
}

/**
 * Detected genres per track URI, saved to files/genre.json a couple of
 * seconds after each change (like [AnalysisStore]). Results from another
 * model count as missing. Failed songs aren't retried until the file
 * changes (a different length).
 */
class GenreStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    private val _genres = MutableStateFlow(read())
    val genres: StateFlow<Map<String, DetectedGenre>> = _genres.asStateFlow()

    /** [track]'s result, if there is one for its current file and the current model. */
    fun genreFor(track: Track): DetectedGenre? {
        val result = _genres.value[track.uri] ?: return null
        if (result.model != GENRE_MODEL_ID) return null
        // Tag edits don't change the audio's length; replacing the file usually does.
        if (track.durationMs > 0 && abs(result.durationMs - track.durationMs) > 1_500) return null
        return result
    }

    fun detectedCount(tracks: List<Track>): Int = tracks.count { genreFor(it) != null }

    fun store(uri: String, result: DetectedGenre) {
        _genres.value = _genres.value + (uri to result)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            val snapshot = _genres.value
            launch(Dispatchers.IO) { writeLock.withLock { write(snapshot) } }
        }
    }

    private fun read(): Map<String, DetectedGenre> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            buildMap {
                for (uri in root.keys()) {
                    val o = root.optJSONObject(uri) ?: continue
                    val status = DetectedGenre.Status.entries.firstOrNull { it.name == o.optString("status") } ?: continue
                    val top = o.optJSONArray("top")
                    put(
                        uri,
                        DetectedGenre(
                            durationMs = o.optLong("duration"),
                            status = status,
                            broad = o.optString("broad").ifEmpty { null },
                            subgenre = o.optString("subgenre").ifEmpty { null },
                            confidence = o.optDouble("confidence", 0.0),
                            top = if (top == null) emptyList() else (0 until top.length()).mapNotNull { i ->
                                top.optJSONArray(i)?.let { it.optString(0) to it.optDouble(1).toFloat() }
                            },
                            model = o.optString("model"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            emptyMap()
        }
    }

    private fun write(all: Map<String, DetectedGenre>) {
        val root = JSONObject()
        for ((uri, g) in all) {
            root.put(uri, JSONObject().apply {
                put("model", g.model)
                put("duration", g.durationMs)
                put("status", g.status.name)
                g.broad?.let { put("broad", it) }
                g.subgenre?.let { put("subgenre", it) }
                put("confidence", g.confidence)
                put("top", JSONArray(g.top.map { (label, score) -> JSONArray().put(label).put(score.toDouble()) }))
            })
        }
        try {
            if (!writeAtomically(file, root.toString())) Log.w(TAG, "Couldn't replace $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private companion object {
        const val TAG = "GenreStore"
        const val FILE_NAME = "genre.json"
    }
}
