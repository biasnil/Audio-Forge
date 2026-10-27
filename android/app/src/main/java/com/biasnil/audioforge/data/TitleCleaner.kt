package com.biasnil.audioforge.data

import java.text.Normalizer

/** Settings -> Download from link: which side of "A - B" is the artist. */
enum class TitleOrder { Automatic, ArtistFirst, TitleFirst }

/**
 * Smart title detection's rules (Settings -> Download from link). Only the
 * user's changes are saved -- [custom] phrases and the built-in ones they
 * turned [disabled] -- so a later version can add built-in phrases without
 * overwriting anyone's choices.
 */
data class TitleRules(
    val enabled: Boolean = true,
    val order: TitleOrder = TitleOrder.Automatic,
    val custom: List<String> = emptyList(),
    val disabled: Set<String> = emptySet(),
) {
    /** Every phrase that's removed when it's the whole text inside brackets. */
    val phrases: List<String> get() = DEFAULT_TITLE_PHRASES.filter { it !in disabled } + custom

    companion object {
        /** Saved with the rules; raise it when the built-in phrases' meaning changes. */
        const val VERSION = 1
    }
}

/**
 * The built-in phrases: only text that never belongs to a song's name. A
 * phrase is removed only when it's everything inside one pair of brackets,
 * so "(Official Video)" goes but "(Live at Wembley)" or "(Remix)" stay.
 */
val DEFAULT_TITLE_PHRASES: List<String> = listOf(
    "Official Video",
    "Official Music Video",
    "Official Audio",
    "Official Lyric Video",
    "Official Visualizer",
    "Official Visualiser",
    "Music Video",
    "Music Visualization",
    "Lyric Video",
    "Lyrics",
    "Audio",
    "Video",
    "Visualizer",
    "MV",
    "HD",
    "4K",
    "Explicit",
)

/** What smart title detection made of a video's name; [original] is kept as it was. */
data class TitleSuggestion(
    val original: String,
    val artist: String,
    val title: String,
    /** The bracketed phrases taken out ("Official Music Video"). */
    val removed: List<String>,
    /** Both orders were plausible, or the name couldn't be cleaned: worth a look before saving. */
    val needsReview: Boolean,
    /** Whether anything differs from the original title and the uploader. */
    val changed: Boolean,
)

/**
 * Smart title detection for Download from link: "Artist - Title" and
 * "Title - Artist", bracketed extras the user chose to remove, and nothing
 * else -- featured artists, remixes and live versions stay. When it can't
 * tell, it keeps the safest guess and says so ([TitleSuggestion.needsReview]).
 * Pure Kotlin, no Android.
 */
object TitleCleaner {
    /** Longer names are left alone (no song is called that; nothing to gain from parsing it). */
    const val MAX_LENGTH = 500

    private val SEPARATOR = Regex("""\s+[-–—]\s+""")
    private val SPACES = Regex("""\s+""")
    private val OPEN = "([{（【「『"
    private val CLOSE = ")]}）】」』"

    fun suggest(rawTitle: String, uploader: String, rules: TitleRules): TitleSuggestion {
        val original = rawTitle
        val uploaderClean = uploader.trim().removeSuffix(" - Topic").trim()
        val unchanged = TitleSuggestion(original, uploaderClean, rawTitle.trim(), emptyList(), needsReview = false, changed = false)
        if (!rules.enabled) return unchanged
        val text = rawTitle.replace(SPACES, " ").trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return unchanged

        // 1. Brackets: drop the ones that hold exactly a phrase to remove.
        val segments = bracketSegments(text)
            ?: // Unmatched brackets: only the spacing is tidied.
            return split(text, uploaderClean, rules.order, original, emptyList(), malformed = true)
        val wanted = rules.phrases.map(::phraseKey).filter { it.isNotEmpty() }.toSet()
        val removed = ArrayList<String>()
        val kept = StringBuilder()
        for (segment in segments) {
            if (segment.inner != null && phraseKey(segment.inner) in wanted) {
                removed += segment.inner.trim()
            } else {
                kept.append(segment.text)
            }
        }
        val cleaned = kept.toString().replace(SPACES, " ").trim().trimEnd('-', '–', '—', '|', ' ').trim()
        // Nothing left (the whole title was "(Official Video)"): keep the original.
        val body = cleaned.ifEmpty { text }
        return split(body, uploaderClean, rules.order, original, if (cleaned.isEmpty()) emptyList() else removed, malformed = false)
    }

    /** 2. Artist and title from "A - B", using the uploader as evidence for which is which. */
    private fun split(
        text: String,
        uploader: String,
        order: TitleOrder,
        original: String,
        removed: List<String>,
        malformed: Boolean,
    ): TitleSuggestion {
        fun result(artist: String, title: String, review: Boolean) = TitleSuggestion(
            original = original,
            artist = artist.trim(),
            title = title.trim(),
            removed = removed,
            needsReview = review || malformed,
            changed = title.trim() != original.trim() || artist.trim() != uploader,
        )

        // Only separators outside brackets count: "Song (A - B Remix)" is one title.
        val separators = topLevelSeparators(text)
        if (separators.isEmpty()) return result(uploader, text, review = false)

        // Each separator gives a candidate: everything before it and everything after.
        val candidates = separators.map { range -> text.substring(0, range.first).trim() to text.substring(range.last + 1).trim() }
            .filter { (left, right) -> left.isNotEmpty() && right.isNotEmpty() }
        if (candidates.isEmpty()) return result(uploader, text, review = false)

        val uploaderKey = nameKey(uploader)
        if (uploaderKey.length >= 2) {
            // The uploader is one side: that side is the artist, whatever the order setting says.
            candidates.firstOrNull { sameArtist(nameKey(it.first), uploaderKey) }?.let { (left, right) ->
                return result(left, right, review = false)
            }
            candidates.lastOrNull { sameArtist(nameKey(it.second), uploaderKey) }?.let { (left, right) ->
                return result(right, left, review = false)
            }
        }
        val several = candidates.size > 1
        return when (order) {
            // Artists rarely have " - " in their name, titles more often: split at the first one.
            TitleOrder.Automatic, TitleOrder.ArtistFirst -> candidates.first().let { (left, right) ->
                result(left, right, review = several && order == TitleOrder.Automatic)
            }
            TitleOrder.TitleFirst -> candidates.last().let { (left, right) -> result(right, left, review = several) }
        }
    }

    private class Segment(val text: String, /** The text inside, for a bracketed segment. */ val inner: String?)

    /** The title cut into plain text and top-level bracketed parts; null if the brackets don't match. */
    private fun bracketSegments(text: String): List<Segment>? {
        val segments = ArrayList<Segment>()
        val stack = ArrayDeque<Char>()
        var start = 0
        var openAt = -1
        for ((i, c) in text.withIndex()) {
            val open = OPEN.indexOf(c)
            val close = CLOSE.indexOf(c)
            when {
                open >= 0 -> {
                    if (stack.isEmpty()) {
                        if (i > start) segments += Segment(text.substring(start, i), null)
                        openAt = i
                    }
                    stack.addLast(CLOSE[open])
                }
                close >= 0 -> {
                    if (stack.isEmpty() || stack.removeLast() != c) return null
                    if (stack.isEmpty()) {
                        // With the space before it, so removing it leaves no double space.
                        segments += Segment(text.substring(openAt, i + 1), text.substring(openAt + 1, i))
                        start = i + 1
                    }
                }
            }
        }
        if (stack.isNotEmpty()) return null
        if (start < text.length) segments += Segment(text.substring(start), null)
        return segments
    }

    private fun topLevelSeparators(text: String): List<IntRange> {
        val depthAt = IntArray(text.length + 1)
        var depth = 0
        for ((i, c) in text.withIndex()) {
            if (OPEN.indexOf(c) >= 0) depth++
            depthAt[i] = depth
            if (CLOSE.indexOf(c) >= 0) depth = (depth - 1).coerceAtLeast(0)
        }
        return SEPARATOR.findAll(text).map { it.range }.filter { depthAt[it.first] == 0 }.toList()
    }

    /** Case, accents, spacing and punctuation don't matter when comparing phrases. */
    fun phraseKey(phrase: String): String =
        stripAccents(phrase).lowercase().replace(Regex("""[\s_.\-]+"""), " ").trim()

    private fun nameKey(name: String): String =
        stripAccents(name).lowercase()
            .removeSuffix(" - topic")
            .replace(Regex("""(official|vevo|music|channel)"""), "")
            .filter { it.isLetterOrDigit() }

    /** Equal, or one starts with the other and they're nearly as long ("Juice WRLD" / "JuiceWRLD999"). */
    private fun sameArtist(side: String, uploader: String): Boolean {
        if (side.isEmpty()) return false
        if (side == uploader) return true
        val (short, long) = if (side.length <= uploader.length) side to uploader else uploader to side
        return short.length >= 4 && long.startsWith(short) && short.length * 10 >= long.length * 6
    }

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
}
