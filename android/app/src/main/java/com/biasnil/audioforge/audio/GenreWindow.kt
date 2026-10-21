package com.biasnil.audioforge.audio

/**
 * The part of a song genre detection listens to: [startMs] for [lengthMs].
 * Plain Kotlin, tested on the JVM.
 */
data class GenreWindow(val startMs: Long, val lengthMs: Long) {
    companion object {
        const val LENGTH_MS = 30_000L
        /** Shorter songs can't be judged. */
        const val MIN_SONG_MS = 5_000L
        /** Songs up to this long are listened to whole. */
        const val WHOLE_SONG_MAX_MS = 40_000L

        /**
         * Unknown length: the first 30 s. Under 5 s: null (too short).
         * 5-40 s: the whole song. Longer: 30 s from a quarter of the way in,
         * past most intros.
         */
        fun forDuration(durationMs: Long): GenreWindow? = when {
            durationMs <= 0 -> GenreWindow(0, LENGTH_MS)
            durationMs < MIN_SONG_MS -> null
            durationMs <= WHOLE_SONG_MAX_MS -> GenreWindow(0, durationMs)
            else -> GenreWindow(durationMs / 4, LENGTH_MS)
        }
    }
}
