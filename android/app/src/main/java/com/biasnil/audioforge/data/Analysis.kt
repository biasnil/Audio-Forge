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
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** What "Analyze Your Songs' Key and BPM" found in a song's audio (the iOS app's SongAnalysis). */
data class SongAnalysis(
    /** Length of the audio that was analyzed; if the file's audio changes, the analysis is redone. */
    val durationMs: Long,
    /** null when the song has no steady beat. */
    val bpm: Double?,
    /** 0..1: how sure the beat detection is (beat-matching needs it to be high). */
    val bpmConfidence: Double,
    /** Time of the first beat (the beat grid is firstBeat + n × 60/bpm). */
    val firstBeatMs: Double,
    /** 0-11 = C...B major, 12-23 = C...B minor; null when unclear. */
    val keyIndex: Int?,
    val keyConfidence: Double,
    /** First and last moments that aren't silence. */
    val audioStartMs: Long,
    val audioEndMs: Long,
    /** Where the ending gets quiet (a fade-out), <= audioEnd. */
    val outroStartMs: Long,
    val version: Int = CURRENT_VERSION,
) {
    val key: MusicKey? get() = keyIndex?.let(::MusicKey)
    /** One beat, in ms. */
    val beatPeriodMs: Double? get() = bpm?.let { 60_000.0 / it }
    /** Steady enough to line beats up with another song. */
    val isBeatReliable: Boolean get() = bpm != null && bpmConfidence >= 0.5

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** A musical key, with its Camelot-wheel code (8A, 8B...) used for harmonic mixing. */
class MusicKey(index: Int) {
    /** 0 = C ... 11 = B. */
    val pitchClass: Int = ((index % 12) + 12) % 12
    val isMinor: Boolean = index >= 12
    val index: Int get() = pitchClass + if (isMinor) 12 else 0

    /** "Am", "E♭". */
    val shortName: String get() = DISPLAY_NAMES[pitchClass] + if (isMinor) "m" else ""
    /** "A minor", "E♭ major". */
    val name: String get() = DISPLAY_NAMES[pitchClass] + if (isMinor) " minor" else " major"
    /** How the key is written into tags (ID3 TKEY / INITIALKEY): "Am", "D#". */
    val tagValue: String get() = TAG_NAMES[pitchClass] + if (isMinor) "m" else ""

    /** 1-12 on the Camelot wheel (C major = 8B, A minor = 8A). */
    val camelotNumber: Int
        get() {
            val majorPitch = if (isMinor) (pitchClass + 3) % 12 else pitchClass // relative major
            return (majorPitch * 7 % 12 + 7) % 12 + 1
        }

    /** "8A" (minor) / "8B" (major). */
    val camelot: String get() = "$camelotNumber${if (isMinor) "A" else "B"}"

    /** Keys that mix well: the same key, its relative major/minor, or one step around the wheel. */
    fun isCompatible(other: MusicKey): Boolean {
        if (camelotNumber == other.camelotNumber) return true
        if (isMinor != other.isMinor) return false
        val difference = abs(camelotNumber - other.camelotNumber)
        return difference == 1 || difference == 11
    }

    override fun equals(other: Any?) = other is MusicKey && other.index == index
    override fun hashCode() = index

    companion object {
        private val DISPLAY_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val TAG_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** Reads a key: "Am", "C#m", "Ebmaj", "8A", "F minor"... */
        fun parse(text: String): MusicKey? {
            val value = text.trim()
            if (value.isEmpty()) return null
            // Camelot code.
            val last = value.last()
            if (last in "AaBb") {
                value.dropLast(1).toIntOrNull()?.takeIf { it in 1..12 }?.let { number ->
                    val minor = last == 'A' || last == 'a'
                    return (0 until 12).map { MusicKey(it + if (minor) 12 else 0) }.first { it.camelotNumber == number }
                }
            }
            val names = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
            var pitch = names[value[0].uppercaseChar()] ?: return null
            var rest = value.drop(1)
            if (rest.startsWith("#") || rest.startsWith("♯")) {
                pitch++
                rest = rest.drop(1)
            } else if (rest.startsWith("b") || rest.startsWith("♭")) {
                pitch--
                rest = rest.drop(1)
            }
            val lower = rest.trim().lowercase()
            val minor = (lower.startsWith("m") && !lower.startsWith("maj")) || lower.startsWith("min")
            return MusicKey(((pitch % 12) + 12) % 12 + if (minor) 12 else 0)
        }
    }
}

/** Analysis results per track URI, saved to files/analysis.json a couple of seconds after each change. */
class AnalysisStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    private val _analysis = MutableStateFlow(read())
    val analysis: StateFlow<Map<String, SongAnalysis>> = _analysis.asStateFlow()

    /** The analysis of [track], if there is one for its current audio. */
    fun analysisFor(track: Track): SongAnalysis? {
        val result = _analysis.value[track.uri] ?: return null
        // Tag edits don't change the audio's length; replacing the file usually does.
        if (track.durationMs > 0 && abs(result.durationMs - track.durationMs) > 1_500) return null
        return result
    }

    fun analyzedCount(tracks: List<Track>): Int = tracks.count { analysisFor(it) != null }

    fun store(uri: String, result: SongAnalysis) {
        _analysis.value = _analysis.value + (uri to result)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            val snapshot = _analysis.value
            launch(Dispatchers.IO) { writeLock.withLock { write(snapshot) } }
        }
    }

    private fun read(): Map<String, SongAnalysis> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            buildMap {
                for (uri in root.keys()) {
                    val o = root.optJSONObject(uri) ?: continue
                    if (o.optInt("version") != SongAnalysis.CURRENT_VERSION) continue
                    put(
                        uri,
                        SongAnalysis(
                            durationMs = o.optLong("duration"),
                            bpm = if (o.has("bpm")) o.optDouble("bpm") else null,
                            bpmConfidence = o.optDouble("bpmConfidence", 0.0),
                            firstBeatMs = o.optDouble("firstBeat", 0.0),
                            keyIndex = if (o.has("key")) o.optInt("key") else null,
                            keyConfidence = o.optDouble("keyConfidence", 0.0),
                            audioStartMs = o.optLong("audioStart"),
                            audioEndMs = o.optLong("audioEnd"),
                            outroStartMs = o.optLong("outroStart"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $FILE_NAME", e)
            emptyMap()
        }
    }

    private fun write(all: Map<String, SongAnalysis>) {
        val root = JSONObject()
        for ((uri, a) in all) {
            root.put(uri, JSONObject().apply {
                put("version", a.version)
                put("duration", a.durationMs)
                a.bpm?.let { put("bpm", it) }
                put("bpmConfidence", a.bpmConfidence)
                put("firstBeat", a.firstBeatMs)
                a.keyIndex?.let { put("key", it) }
                put("keyConfidence", a.keyConfidence)
                put("audioStart", a.audioStartMs)
                put("audioEnd", a.audioEndMs)
                put("outroStart", a.outroStartMs)
            })
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
        const val TAG = "AnalysisStore"
        const val FILE_NAME = "analysis.json"
    }
}

/** "120 BPM", "8A · Am" -- what the Songs list shows next to a song under the BPM / Key sorts. */
fun analysisDetail(analysis: SongAnalysis?, sort: TrackSort): String? {
    if (analysis == null) return null
    val bpm = analysis.bpm?.let { "${Math.round(it)} BPM" }
    val key = analysis.key?.let { "${it.camelot} · ${it.shortName}" }
    val parts = if (sort == TrackSort.Bpm) listOf(bpm, key) else listOf(key, bpm)
    return parts.filterNotNull().joinToString("  ").ifEmpty { null }
}

// --- Harmonic shuffle ---------------------------------------------------------------

/**
 * Greedy DJ-style order (the iOS app's harmonicOrder): from the current
 * song, the next one is the best match among a few random candidates (so it
 * stays a shuffle, just a smooth one). Returns indices, [start] first.
 */
fun harmonicOrder(
    items: List<Track>,
    start: Int,
    analysisFor: (Track) -> SongAnalysis?,
    random: Random = Random.Default,
): List<Int> {
    if (start !in items.indices) return items.indices.toList()
    val analyses = items.map(analysisFor)
    val pool = items.indices.toMutableList()
    pool[start] = pool[pool.lastIndex]
    pool.removeAt(pool.lastIndex)
    val order = ArrayList<Int>(items.size)
    order += start
    var current = start
    while (pool.isNotEmpty()) {
        var bestPosition = random.nextInt(pool.size)
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(minOf(10, pool.size)) {
            val position = random.nextInt(pool.size)
            val score = compatibility(analyses[current], analyses[pool[position]]) + random.nextDouble(0.5)
            if (score > bestScore) {
                bestScore = score
                bestPosition = position
            }
        }
        current = pool[bestPosition]
        order += current
        pool[bestPosition] = pool[pool.lastIndex]
        pool.removeAt(pool.lastIndex)
    }
    return order
}

/** How well two songs follow each other: same/compatible key, similar tempo (half/double time counts). */
fun compatibility(a: SongAnalysis?, b: SongAnalysis?): Double {
    if (a == null || b == null) return 0.0
    var score = 0.0
    val keyA = a.key
    val keyB = b.key
    if (keyA != null && keyB != null) {
        if (keyA == keyB) score += 3 else if (keyA.isCompatible(keyB)) score += 2
    }
    val bpmA = a.bpm
    val bpmB = b.bpm
    if (bpmA != null && bpmB != null) {
        val ratio = listOf(bpmA / bpmB, bpmA * 2 / bpmB, bpmA / (bpmB * 2)).minOf { max(it, 1 / it) }
        if (ratio <= 1.06) score += 2 else if (ratio <= 1.12) score += 1
    }
    return score
}
