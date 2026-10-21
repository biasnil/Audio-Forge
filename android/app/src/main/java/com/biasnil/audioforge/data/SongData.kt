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
import org.json.JSONObject
import java.io.File

/** What the app learns about a song while you use it (the iOS app's SongStats). */
data class SongStats(
    val playCount: Int = 0,
    /** System.currentTimeMillis() of the last counted play; 0 = never. */
    val lastPlayedMs: Long = 0,
    val favorite: Boolean = false,
    /** Long tracks (audiobooks, mixes): where you stopped. */
    val resumePositionMs: Long? = null,
    /** Lyrics timing adjustment (+ shows lines later). */
    val lyricsOffsetMs: Long = 0,
    /** Podcast episodes: listened to the end (or marked as played). */
    val played: Boolean = false,
)

/**
 * Play counts, Loved songs, resume positions and lyrics offsets, per track
 * URI. Saved to files/songstats.json a second after each change.
 */
class SongDataStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    private val _stats = MutableStateFlow(read())
    val stats: StateFlow<Map<String, SongStats>> = _stats.asStateFlow()

    fun statsFor(uri: String): SongStats = _stats.value[uri] ?: SongStats()

    fun isFavorite(uri: String): Boolean = _stats.value[uri]?.favorite == true

    fun toggleFavorite(uri: String) = update(uri) { it.copy(favorite = !it.favorite) }

    fun setFavorite(uris: Collection<String>, favorite: Boolean) {
        val current = _stats.value.toMutableMap()
        for (uri in uris) current[uri] = (current[uri] ?: SongStats()).copy(favorite = favorite)
        _stats.value = current
        scheduleSave()
    }

    fun recordPlay(uri: String) = update(uri) {
        it.copy(playCount = it.playCount + 1, lastPlayedMs = System.currentTimeMillis())
    }

    fun setResumePosition(uri: String, positionMs: Long?) {
        if (statsFor(uri).resumePositionMs == positionMs) return
        update(uri) { it.copy(resumePositionMs = positionMs) }
    }

    fun setLyricsOffset(uri: String, offsetMs: Long) = update(uri) { it.copy(lyricsOffsetMs = offsetMs) }

    /** Podcasts: marks an episode played or not; marking it played also forgets where it was left. */
    fun setPlayed(uri: String, played: Boolean) {
        val current = statsFor(uri)
        if (current.played == played && (!played || current.resumePositionMs == null)) return
        update(uri) { it.copy(played = played, resumePositionMs = if (played) null else it.resumePositionMs) }
    }

    private fun update(uri: String, change: (SongStats) -> SongStats) {
        val current = _stats.value
        _stats.value = current + (uri to change(current[uri] ?: SongStats()))
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            val snapshot = _stats.value
            launch(Dispatchers.IO) { writeLock.withLock { write(snapshot) } }
        }
    }

    private fun read(): Map<String, SongStats> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val result = HashMap<String, SongStats>(root.length() * 2)
            for (uri in root.keys()) {
                val obj = root.optJSONObject(uri) ?: continue
                result[uri] = SongStats(
                    playCount = obj.optInt("playCount", 0),
                    lastPlayedMs = obj.optLong("lastPlayed", 0),
                    favorite = obj.optBoolean("favorite", false),
                    resumePositionMs = if (obj.has("resumeMs")) obj.optLong("resumeMs") else null,
                    lyricsOffsetMs = obj.optLong("lyricsOffsetMs", 0),
                    played = obj.optBoolean("played", false),
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            emptyMap()
        }
    }

    private fun write(stats: Map<String, SongStats>) {
        val root = JSONObject()
        for ((uri, value) in stats) {
            if (value == SongStats()) continue // nothing worth keeping
            val obj = JSONObject()
            if (value.playCount > 0) obj.put("playCount", value.playCount)
            if (value.lastPlayedMs > 0) obj.put("lastPlayed", value.lastPlayedMs)
            if (value.favorite) obj.put("favorite", true)
            value.resumePositionMs?.let { obj.put("resumeMs", it) }
            if (value.lyricsOffsetMs != 0L) obj.put("lyricsOffsetMs", value.lyricsOffsetMs)
            if (value.played) obj.put("played", true)
            root.put(uri, obj)
        }
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) Log.w(TAG, "Couldn't replace $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private companion object {
        const val TAG = "SongDataStore"
        const val FILE_NAME = "songstats.json"
    }
}
