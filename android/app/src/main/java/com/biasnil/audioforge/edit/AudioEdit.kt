package com.biasnil.audioforge.edit

import kotlin.math.log10
import kotlin.math.pow

/** Part of the original song, in frames (one sample per channel): [start, end). */
data class Span(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/**
 * A song being edited, without touching it: which parts of the original
 * are kept, in order, and how much louder or quieter it is. Cutting a part
 * closes the gap (ripple), so positions after it move earlier. Positions
 * here are frames on the edited timeline unless they say "source".
 */
data class AudioEdit(val spans: List<Span>, val gainDb: Float = 0f) {

    val length: Long get() = spans.sumOf { it.length }

    /** Removes [from, to) of the edited song; what follows moves up to close the gap. */
    fun cut(from: Long, to: Long): AudioEdit {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(0, length)
        if (b <= a) return this
        val kept = ArrayList<Span>()
        var at = 0L
        for (span in spans) {
            val spanStart = at
            val spanEnd = at + span.length
            at = spanEnd
            // The part before the cut, and the part after it.
            if (a > spanStart) kept += Span(span.start, span.start + (minOf(a, spanEnd) - spanStart))
            if (b < spanEnd) kept += Span(span.start + (maxOf(b, spanStart) - spanStart), span.end)
        }
        return copy(spans = kept.filter { it.length > 0 })
    }

    /** Keeps only [from, to) (trims the start and the end). */
    fun keepOnly(from: Long, to: Long): AudioEdit {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(0, length)
        if (b <= a) return this
        return cut(b, length).cut(0, a)
    }

    /** Where [position] on the edited timeline is in the original song. */
    fun toSource(position: Long): Long {
        var at = 0L
        for (span in spans) {
            if (position < at + span.length) return span.start + (position - at).coerceAtLeast(0)
            at += span.length
        }
        return spans.lastOrNull()?.end ?: 0
    }

    /** The original song's parts that play from [position] on, in order. */
    fun spansFrom(position: Long): List<Span> {
        val out = ArrayList<Span>()
        var at = 0L
        for (span in spans) {
            val end = at + span.length
            if (position < end) out += Span(span.start + (position - at).coerceAtLeast(0), span.end)
            at = end
        }
        return out
    }

    val changed: Boolean get() = gainDb != 0f || spans.size != 1 || spans.firstOrNull()?.start != 0L

    companion object {
        const val MAX_GAIN_DB = 12f

        /** The whole song, unchanged. */
        fun whole(frames: Long) = AudioEdit(listOf(Span(0, frames)))
    }
}

/** dB -> the factor samples are multiplied by. */
fun gainFactor(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

/**
 * Normalize: the volume change that brings the loudest moment ([peak],
 * 0..1 at the current volume of 0 dB) to just under full scale (-1 dB),
 * within what the slider allows.
 */
fun normalizeGainDb(peak: Float): Float {
    if (peak <= 0f) return 0f
    val db = (20 * log10(NORMALIZE_TARGET / peak.toDouble())).toFloat()
    return (Math.round(db * 10) / 10f).coerceIn(-AudioEdit.MAX_GAIN_DB, AudioEdit.MAX_GAIN_DB)
}

private const val NORMALIZE_TARGET = 0.891 // -1 dBFS
