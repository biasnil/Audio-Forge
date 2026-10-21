package com.biasnil.audioforge.data

/**
 * Turns YAMNet's label scores into a broad genre and, where one stands out,
 * a subgenre. Plain Kotlin, tested on the JVM.
 *
 * The genre names are English data (they can go into the GENRE tag), so
 * they are never translated. Labels are looked up by YAMNet's display_name,
 * never by class index. Changing the table means a new [GENRE_MODEL_ID].
 */
object GenreTaxonomy {
    /** A label counts towards its broad genre; [subgenre] = its name as a subgenre, if it is one. */
    data class Entry(val label: String, val broad: String, val subgenre: String?)

    val TABLE: List<Entry> = buildList {
        fun genre(broad: String, vararg labels: Pair<String, String?>) =
            labels.forEach { (label, sub) -> add(Entry(label, broad, sub)) }
        genre("Pop", "Pop music" to null)
        genre(
            "Rock", "Rock music" to null, "Punk rock" to "Punk", "Grunge" to "Grunge",
            "Progressive rock" to "Progressive Rock", "Rock and roll" to "Rock & Roll",
            "Psychedelic rock" to "Psychedelic", "Independent music" to "Indie",
        )
        genre("Metal", "Heavy metal" to null)
        genre("Hip-Hop", "Hip hop music" to null)
        genre("R&B / Soul", "Rhythm and blues" to null, "Soul music" to "Soul", "Funk" to "Funk")
        genre(
            "Electronic", "Electronic music" to null, "Electronica" to null, "Electronic dance music" to "EDM",
            "Dance music" to "Dance", "House music" to "House", "Techno" to "Techno", "Dubstep" to "Dubstep",
            "Drum and bass" to "Drum & Bass", "Trance music" to "Trance", "Disco" to "Disco",
            "Ambient music" to "Ambient", "New-age music" to "New Age",
        )
        genre("Jazz", "Jazz" to null, "Swing music" to "Swing")
        genre("Blues", "Blues" to null)
        genre("Classical", "Classical music" to null, "Opera" to "Opera")
        genre("Country", "Country" to null, "Bluegrass" to "Bluegrass")
        genre("Folk", "Folk music" to null, "Traditional music" to "Traditional")
        genre("Latin", "Music of Latin America" to null, "Salsa music" to "Salsa", "Flamenco" to "Flamenco")
        genre("Reggae", "Reggae" to null, "Ska" to "Ska")
        genre("Gospel", "Gospel music" to null, "Christian music" to "Christian")
        genre(
            "World", "Middle Eastern music" to null, "Music of Africa" to "African", "Afrobeat" to "Afrobeat",
            "Music of Asia" to "Asian", "Carnatic music" to "Carnatic", "Music of Bollywood" to "Bollywood",
        )
        genre(
            "Soundtrack", "Soundtrack music" to null, "Video game music" to "Video Game",
            "Theme music" to null, "Christmas music" to "Christmas",
        )
    }

    const val MUSIC_LABEL = "Music"
    const val SPEECH_LABEL = "Speech"

    /** Below this "Music" score, with more speech than music: not music. */
    const val NOT_MUSIC_MAX = 0.15
    /** Less genre evidence than this in total: unknown. */
    const val MIN_TOTAL = 0.05
    /** The broad genre's share of the evidence must be at least this. */
    const val MIN_CONFIDENCE = 0.35
    /** A subgenre label must carry at least this share of its broad genre's evidence. */
    const val SUBGENRE_SHARE = 0.4
    const val TOP_COUNT = 5

    /**
     * The result for one song from [scores] (YAMNet's label -> score,
     * averaged over the song's frames).
     */
    fun decide(scores: Map<String, Float>, durationMs: Long): DetectedGenre {
        val top = scores.entries.sortedByDescending { it.value }.take(TOP_COUNT).map { it.key to it.value }
        fun result(status: DetectedGenre.Status, broad: String? = null, sub: String? = null, confidence: Double = 0.0) =
            DetectedGenre(durationMs, status, broad, sub, confidence, top)

        val music = scores[MUSIC_LABEL] ?: 0f
        val speech = scores[SPEECH_LABEL] ?: 0f
        if (music < NOT_MUSIC_MAX && speech > music) return result(DetectedGenre.Status.NOT_MUSIC)

        val mass = LinkedHashMap<String, Double>()
        for (entry in TABLE) mass[entry.broad] = (mass[entry.broad] ?: 0.0) + (scores[entry.label] ?: 0f)
        val total = mass.values.sum()
        if (total < MIN_TOTAL) return result(DetectedGenre.Status.UNKNOWN)
        val (broad, broadMass) = mass.maxBy { it.value }
        val confidence = broadMass / total
        if (confidence < MIN_CONFIDENCE) return result(DetectedGenre.Status.UNKNOWN, confidence = confidence)

        val sub = TABLE.filter { it.broad == broad && it.subgenre != null }
            .maxByOrNull { scores[it.label] ?: 0f }
            ?.takeIf { (scores[it.label] ?: 0f) >= SUBGENRE_SHARE * broadMass }
            ?.subgenre
        return result(DetectedGenre.Status.OK, broad, sub, confidence)
    }
}
