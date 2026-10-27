package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The tags read from added-folder files, kept between scans in
 * files/folder_tags.json, so a scan only opens files that are new or whose
 * size or date changed -- with hundreds of songs, re-reading every file on
 * each launch kept the phone busy for a long time. The music files are
 * only ever read.
 */
class FolderTagCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private var entries: MutableMap<String, Entry>? = null
    private var changed = false

    private class Entry(
        val size: Long,
        val modifiedMs: Long,
        val title: String,
        val artist: String,
        val album: String,
        val genre: String,
        val year: Int,
        val trackNumber: Int,
        val durationMs: Long,
        val bitrateKbps: Int,
        val sampleRateHz: Int,
    )

    /** [base] with its tags filled from the cache, if the file hasn't changed since; else null. */
    @Synchronized
    fun lookup(base: Track): Track? {
        val entry = load()[base.uri] ?: return null
        if (entry.size != base.sizeBytes || entry.modifiedMs != base.dateAddedMs) return null
        return base.copy(
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            genre = entry.genre,
            year = entry.year,
            trackNumber = entry.trackNumber,
            durationMs = entry.durationMs,
            bitrateKbps = entry.bitrateKbps,
            sampleRateHz = entry.sampleRateHz,
        )
    }

    /** Remembers [track]'s tags, read just now from its file. */
    @Synchronized
    fun remember(track: Track) {
        load()[track.uri] = Entry(
            track.sizeBytes, track.dateAddedMs, track.title, track.artist, track.album, track.genre,
            track.year, track.trackNumber, track.durationMs, track.bitrateKbps, track.sampleRateHz,
        )
        changed = true
    }

    /** Drops files under [treeKey] that a scan didn't find any more. */
    @Synchronized
    fun keepOnly(treeKey: String, seen: Set<String>) {
        if (load().keys.removeAll { it.startsWith(treeKey) && it !in seen }) changed = true
    }

    /** Writes the cache if anything changed (call after a scan). */
    @Synchronized
    fun save() {
        if (!changed) return
        val root = JSONObject()
        for ((uri, e) in load()) {
            root.put(
                uri,
                JSONObject()
                    .put("size", e.size).put("modified", e.modifiedMs)
                    .put("title", e.title).put("artist", e.artist).put("album", e.album).put("genre", e.genre)
                    .put("year", e.year).put("track", e.trackNumber).put("duration", e.durationMs)
                    .put("bitrate", e.bitrateKbps).put("sampleRate", e.sampleRateHz),
            )
        }
        try {
            if (writeAtomically(file, root.toString())) changed = false
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        val map = HashMap<String, Entry>()
        try {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                for (uri in root.keys()) {
                    val o = root.getJSONObject(uri)
                    map[uri] = Entry(
                        o.optLong("size"), o.optLong("modified"), o.optString("title"), o.optString("artist"),
                        o.optString("album"), o.optString("genre"), o.optInt("year"), o.optInt("track"),
                        o.optLong("duration"), o.optInt("bitrate"), o.optInt("sampleRate"),
                    )
                }
            }
        } catch (e: Exception) {
            // A damaged cache only means the files are read again.
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
        }
        entries = map
        return map
    }

    private companion object {
        const val TAG = "FolderTagCache"
        const val FILE_NAME = "folder_tags.json"
    }
}
