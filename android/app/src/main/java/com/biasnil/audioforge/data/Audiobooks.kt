package com.biasnil.audioforge.data

import java.util.Locale

// Audiobooks: .m4b files, files Android itself files as audiobooks, and
// songs in folders marked "Audiobook" in the Folders tab. Like podcasts they
// only show in their own tab (not Songs, Albums, shuffle, smart playlists),
// are never analyzed, and always continue where they were left. Plain
// Kotlin, tested on the JVM.

/** One chapter of a file: its name (may be empty) and where it starts. */
data class Chapter(val title: String, val startMs: Long)

/**
 * One book: its files ([parts]) in order -- a single .m4b, or a folder of
 * MP3s. [key] identifies it across launches (bookmarks, last part played).
 */
data class Audiobook(val key: String, val title: String, val author: String, val parts: List<Track>) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }
}

/**
 * Whether [track] is an audiobook: an .m4b file, one Android files as an
 * audiobook, or one in an audiobook folder -- an added folder in
 * [audiobookFolders] (by tree URI), or a phone-library folder in it or
 * inside one (by folder key).
 */
fun isAudiobook(track: Track, audiobookFolders: Set<String>): Boolean {
    if (track.systemAudiobook || track.fileName.endsWith(".m4b", ignoreCase = true)) return true
    if (audiobookFolders.isEmpty()) return false
    val source = track.sourceFolder
    if (source != null) return source in audiobookFolders
    return audiobookFolders.any { isInFolder(track.folderKey, it) }
}

/**
 * Files grouped into books: by Album tag (and artist), so a book split into
 * many files is one book; a file with no Album tag is a book of its own.
 * Parts in track-number order, then by file name; books A-Z.
 */
fun audiobooks(tracks: List<Track>): List<Audiobook> =
    tracks.groupBy { bookKey(it) }
        .map { (key, parts) ->
            val sorted = parts.sortedWith(
                compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
            )
            val first = sorted.first()
            Audiobook(
                key = key,
                title = first.album.trim().ifEmpty { first.title },
                author = sorted.firstNotNullOfOrNull { it.artist.trim().ifEmpty { null } }.orEmpty(),
                parts = sorted,
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

/** "album|artist" in lower case, or the file's own URI when it has no Album tag. */
fun bookKey(track: Track): String {
    val album = track.album.trim()
    if (album.isEmpty()) return track.uri
    return album.lowercase(Locale.ROOT) + "|" + track.artist.trim().lowercase(Locale.ROOT)
}

/** How far into a book you are: the part you're on and the time listened, out of the whole. */
data class BookProgress(val partIndex: Int, val listenedMs: Long, val totalMs: Long) {
    val fraction: Float get() = if (totalMs > 0) (listenedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (totalMs - listenedMs).coerceAtLeast(0)
}

/**
 * [book]'s progress: every part before the one last played ([lastUri]) counts
 * as heard, plus where that part was left ([resumeMs]), or all of it if it
 * was played to the end ([played]). Nothing played yet = the start.
 */
fun bookProgress(
    book: Audiobook,
    lastUri: String?,
    resumeMs: (String) -> Long?,
    played: (String) -> Boolean,
): BookProgress {
    val index = book.parts.indexOfFirst { it.uri == lastUri }
    if (index < 0) return BookProgress(0, 0, book.durationMs)
    val part = book.parts[index]
    val before = book.parts.take(index).sumOf { it.durationMs }
    val within = resumeMs(part.uri) ?: if (played(part.uri)) part.durationMs else 0L
    return BookProgress(index, before + within.coerceIn(0, part.durationMs.coerceAtLeast(0)), book.durationMs)
}

/** The chapter playing at [positionMs] (-1 before the first one, or with none). */
fun currentChapterIndex(chapters: List<Chapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs }

/**
 * Where "previous chapter" goes: this chapter's start if you're more than
 * [RESTART_MS] into it, else the one before; null = there's none in this
 * file (go to the previous file instead).
 */
fun previousChapterStart(chapters: List<Chapter>, positionMs: Long): Long? {
    val index = currentChapterIndex(chapters, positionMs)
    if (index < 0) return null
    if (positionMs - chapters[index].startMs > RESTART_MS) return chapters[index].startMs
    return chapters.getOrNull(index - 1)?.startMs
}

/** Where "next chapter" goes; null = this is the last one (go to the next file instead). */
fun nextChapterStart(chapters: List<Chapter>, positionMs: Long): Long? =
    chapters.getOrNull(currentChapterIndex(chapters, positionMs) + 1)?.startMs

/** Like the previous-song button: past this, "previous" restarts the current chapter. */
private const val RESTART_MS = 3_000L
