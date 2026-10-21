package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** What was played: Listening stats count music and podcasts separately (radio will be a third kind). */
enum class PlayKind(val id: String) {
    Music("m"),
    Podcast("p");

    companion object {
        fun fromId(id: String?): PlayKind = entries.firstOrNull { it.id == id } ?: Music
    }
}

/**
 * One play in the listening log (Settings -> Listening stats). The title,
 * artist and album are kept as they were when it played, so the stats still
 * make sense after a file is renamed, retagged or deleted.
 */
data class PlayEntry(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    /** System.currentTimeMillis() when the song started. */
    val startedAtMs: Long,
    /** Time actually spent listening (real time while playing). */
    val listenedMs: Long,
    val kind: PlayKind = PlayKind.Music,
)

/** A play counts once it's been heard for 30 seconds, or half the song if that's shorter. */
fun playCountsAfterMs(lengthMs: Long): Long =
    if (lengthMs > 0) minOf(COUNT_AFTER_MS, lengthMs / 2) else COUNT_AFTER_MS

private const val COUNT_AFTER_MS = 30_000L

/**
 * Follows the song being played and how long it has really been heard: real
 * time while playing, so seeking ahead or a faster speed don't add to it.
 * Plain Kotlin; PlaybackManager feeds it.
 */
class ListenTracker {
    private var current: PlayEntry? = null
    private var lengthMs = 0L

    /** A new song; returns the one before it if it counted as a play. */
    fun start(entry: PlayEntry, lengthMs: Long): PlayEntry? {
        val finished = finish()
        current = entry.copy(listenedMs = 0)
        this.lengthMs = lengthMs
        return finished
    }

    /** The song's length, once the player knows it. */
    fun setLength(lengthMs: Long) {
        if (lengthMs > 0) this.lengthMs = lengthMs
    }

    /** [deltaMs] more of listening (capped, so a stalled clock can't add hours). */
    fun listened(deltaMs: Long) {
        current = current?.let { it.copy(listenedMs = it.listenedMs + deltaMs.coerceIn(0, MAX_STEP_MS)) }
    }

    /** The play so far, if it already counts. */
    val counted: PlayEntry? get() = current?.takeIf { it.listenedMs >= playCountsAfterMs(lengthMs) }

    /** Ends the current song; returns it if it counted. */
    fun finish(): PlayEntry? {
        val done = counted
        current = null
        return done
    }

    private companion object {
        const val MAX_STEP_MS = 1_000L
    }
}

/** One log line (JSON). Short keys: the log grows by a line per play. */
fun PlayEntry.toJsonLine(): String = JSONObject().apply {
    put("u", uri)
    put("ti", title)
    if (artist.isNotEmpty()) put("ar", artist)
    if (album.isNotEmpty()) put("al", album)
    put("t", startedAtMs)
    put("l", listenedMs)
    if (kind != PlayKind.Music) put("k", kind.id)
}.toString()

/** Null for a line that isn't a play (a half-written last line after a crash). */
fun parsePlayEntry(line: String): PlayEntry? = try {
    val obj = JSONObject(line)
    PlayEntry(
        uri = obj.getString("u"),
        title = obj.optString("ti"),
        artist = obj.optString("ar"),
        album = obj.optString("al"),
        startedAtMs = obj.getLong("t"),
        listenedMs = obj.getLong("l"),
        kind = PlayKind.fromId(obj.optString("k").ifEmpty { null }),
    )
} catch (e: Exception) {
    null
}

/**
 * The listening log: one line per play in files/playlog.jsonl, appended as
 * songs finish. It has its own file so SongDataStore stays small. The song
 * playing now is saved separately (files/playlog-current.json) every few
 * seconds once it counts, so a play isn't lost if the app is killed; the
 * next launch adds it to the log.
 */
class PlayLogStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, FILE_NAME)
    private val currentFile = File(context.filesDir, CURRENT_FILE_NAME)
    /** One write at a time, in the order asked: loading first, then appends and checkpoints. */
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _entries = MutableStateFlow<List<PlayEntry>>(emptyList())
    /** Oldest first. */
    val entries: StateFlow<List<PlayEntry>> = _entries.asStateFlow()

    init {
        scope.launch(io) {
            val leftOver = readCurrent()
            if (leftOver != null) {
                appendLine(leftOver)
                currentFile.delete()
            }
            val loaded = readAll()
            // Plays added while loading come after the saved ones.
            _entries.update { loaded + it }
        }
    }

    /** A finished play: added to the log, and the saved current play cleared. */
    fun append(entry: PlayEntry) {
        _entries.update { it + entry }
        scope.launch(io) {
            appendLine(entry)
            currentFile.delete()
        }
    }

    /** Saves the play in progress (null clears it). */
    fun checkpoint(entry: PlayEntry?) {
        scope.launch(io) {
            try {
                if (entry == null) currentFile.delete() else currentFile.writeText(entry.toJsonLine())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save $CURRENT_FILE_NAME", e)
            }
        }
    }

    private fun appendLine(entry: PlayEntry) {
        try {
            file.appendText(entry.toJsonLine() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't add to $FILE_NAME", e)
        }
    }

    private fun readAll(): List<PlayEntry> = try {
        if (file.exists()) file.readLines().mapNotNull { it.takeIf(String::isNotBlank)?.let(::parsePlayEntry) } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read $FILE_NAME", e)
        emptyList()
    }

    private fun readCurrent(): PlayEntry? = try {
        if (currentFile.exists()) parsePlayEntry(currentFile.readText()) else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "PlayLogStore"
        const val FILE_NAME = "playlog.jsonl"
        const val CURRENT_FILE_NAME = "playlog-current.json"
    }
}
