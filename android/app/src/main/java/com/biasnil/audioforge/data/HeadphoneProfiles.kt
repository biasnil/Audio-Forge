package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** One filter of a parametric EQ: a peak/dip, or a low/high shelf. */
data class ParametricFilter(val type: Type, val frequencyHz: Double, val gainDb: Double, val q: Double) {
    enum class Type(val code: Char) { Peaking('P'), LowShelf('L'), HighShelf('H') }
}

/**
 * AutoEq's correction for one headphone as measured by one [source]
 * (oratory1990, crinacle, ...): turn everything down by [preampDb] so the
 * boosts can't clip, then the [filters]. [kind] is AutoEq's folder name,
 * e.g. "over-ear" or "711 in-ear" (the measuring rig and fit).
 */
data class HeadphoneProfile(
    val name: String,
    val source: String,
    val kind: String,
    val preampDb: Double,
    val filters: List<ParametricFilter>,
)

/** "L105,6.4,0.7 P8800,5.1,1.42 ..." -- the filter format of assets/autoeq.tsv and audioforge.json. */
fun encodeFilters(filters: List<ParametricFilter>): String =
    filters.joinToString(" ") { "${it.type.code}${it.frequencyHz.short()},${it.gainDb.short()},${it.q.short()}" }

/** The reverse of [encodeFilters]; filters that don't parse are left out. */
fun decodeFilters(text: String): List<ParametricFilter> =
    text.split(' ').mapNotNull { token ->
        val type = ParametricFilter.Type.entries.firstOrNull { it.code == token.firstOrNull() } ?: return@mapNotNull null
        val numbers = token.drop(1).split(',').map { it.toDoubleOrNull() }
        if (numbers.size != 3 || numbers.any { it == null || !it.isFinite() }) return@mapNotNull null
        val (frequency, gain, q) = numbers.map { it!! }
        if (frequency <= 0 || q <= 0) null else ParametricFilter(type, frequency, gain, q)
    }

/** One line of assets/autoeq.tsv (see tools/autoeq_import.py), or null for a comment or a bad line. */
fun parseHeadphoneProfile(line: String): HeadphoneProfile? {
    if (line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size != 5) return null
    val preamp = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val filters = decodeFilters(fields[4])
    if (fields[0].isBlank() || filters.isEmpty()) return null
    return HeadphoneProfile(fields[0], fields[1], fields[2], preamp, filters)
}

/**
 * Profiles whose name contains every word of [query] (any order, any case),
 * in the file's order: by name, the best-known sources first.
 */
fun searchHeadphones(profiles: List<HeadphoneProfile>, query: String, limit: Int): List<HeadphoneProfile> {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return profiles.take(limit)
    return profiles.asSequence()
        .filter { profile -> profile.name.lowercase(Locale.ROOT).let { name -> words.all { it in name } } }
        .take(limit)
        .toList()
}

/** The ~9,000 AutoEq profiles bundled in assets/autoeq.tsv, read once on first use. */
class HeadphoneProfileRepository(private val context: Context) {
    private val lock = Mutex()
    private var profiles: List<HeadphoneProfile>? = null

    suspend fun all(): List<HeadphoneProfile> = lock.withLock {
        profiles ?: withContext(Dispatchers.IO) {
            try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.mapNotNull(::parseHeadphoneProfile).toList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read $ASSET", e)
                emptyList()
            }
        }.also { profiles = it }
    }

    private companion object {
        const val TAG = "HeadphoneProfiles"
        const val ASSET = "autoeq.tsv"
    }
}

/** 105.0 -> "105", 0.70 -> "0.7". */
private fun Double.short(): String =
    if (this == Math.rint(this) && kotlin.math.abs(this) < 1e15) toLong().toString() else toString()
