package com.biasnil.audioforge.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the current song was, saved separately from the queue because it changes all the time. */
data class SavedPosition(val uri: String, val positionMs: Long)

/**
 * The queue between launches: files/queue.json (song URIs + shuffle state),
 * rewritten when the queue changes, and the playback position in
 * SharedPreferences, saved on pause and every few seconds while playing.
 * Kept out of AppStore's settings file, which is rewritten on every
 * settings change and would otherwise carry a library-sized queue.
 */
class QueueStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val writeLock = Mutex()

    suspend fun load(): SavedQueue<String>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val root = JSONObject(file.readText())
            val items = root.getJSONArray("items").let { array -> List(array.length()) { array.getString(it) } }
            SavedQueue(
                items = items,
                currentIndex = root.optInt("current", 0),
                history = root.optJSONArray("history").toIntList(),
                shuffleOrder = root.optJSONArray("shuffleOrder").toIntList(),
                shufflePos = root.optInt("shufflePos", -1),
                userQueued = root.optJSONArray("userQueued").toIntList(),
                shuffleMode = enumOrDefault(root.optString("shuffle"), ShuffleMode.Off),
                repeatMode = enumOrDefault(root.optString("repeat"), RepeatMode.Off),
                context = root.optString("context").ifEmpty { null },
            ).takeIf { it.items.isNotEmpty() }
        } catch (e: Exception) {
            // A corrupt file just means starting with an empty queue.
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            null
        }
    }

    /** Null deletes the saved queue. */
    suspend fun save(saved: SavedQueue<String>?) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                if (saved == null) {
                    file.delete()
                    return@withLock
                }
                val root = JSONObject()
                    .put("version", 1)
                    .put("items", JSONArray(saved.items))
                    .put("current", saved.currentIndex)
                    .put("history", JSONArray(saved.history))
                    .put("shuffleOrder", JSONArray(saved.shuffleOrder))
                    .put("shufflePos", saved.shufflePos)
                    .put("userQueued", JSONArray(saved.userQueued))
                    .put("shuffle", saved.shuffleMode.name)
                    .put("repeat", saved.repeatMode.name)
                    .put("context", saved.context.orEmpty())
                // Write-then-rename so a crash mid-write never leaves a truncated file.
                val temp = File(file.parentFile, "$FILE_NAME.tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(file)) Log.w(TAG, "Couldn't replace $FILE_NAME")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save $FILE_NAME", e)
            }
        }
    }

    fun loadPosition(): SavedPosition? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return SavedPosition(uri, prefs.getLong(KEY_POSITION, 0L))
    }

    /** Asynchronous (SharedPreferences.apply), so it's fine on the main thread. */
    fun savePosition(uri: String, positionMs: Long) {
        prefs.edit().putString(KEY_URI, uri).putLong(KEY_POSITION, positionMs).apply()
    }

    private companion object {
        const val TAG = "QueueStore"
        const val FILE_NAME = "queue.json"
        const val PREFS_NAME = "playback"
        const val KEY_URI = "positionUri"
        const val KEY_POSITION = "positionMs"
    }
}

private fun JSONArray?.toIntList(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it, -1) }.filter { it >= 0 }

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
