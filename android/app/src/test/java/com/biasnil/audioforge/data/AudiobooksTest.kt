package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobooksTest {

    private fun track(uri: String, file: String, album: String = "", artist: String = "", number: Int = 0, ms: Long = 60_000, folder: String = "") =
        Track(uri = uri, title = file.substringBeforeLast('.'), fileName = file, album = album, artist = artist, trackNumber = number, durationMs = ms, folderKey = folder)

    @Test
    fun whatCountsAsABook() {
        assertTrue(isAudiobook(track("a", "Dune.m4b"), emptySet()))
        assertTrue(isAudiobook(track("a", "x.mp3").copy(systemAudiobook = true), emptySet()))
        assertFalse(isAudiobook(track("a", "song.mp3", folder = "external_primary|Music/"), setOf("external_primary|Books/")))
        assertTrue(isAudiobook(track("a", "01.mp3", folder = "external_primary|Books/Dune/"), setOf("external_primary|Books/")))
        assertTrue(isAudiobook(track("a", "01.mp3").copy(sourceFolder = "tree:books"), setOf("tree:books")))
    }

    @Test
    fun filesGroupIntoBooksByAlbumInOrder() {
        val books = audiobooks(
            listOf(
                track("3", "c.mp3", album = "Dune", artist = "Frank Herbert", number = 3),
                track("1", "a.mp3", album = "Dune", artist = "Frank Herbert", number = 1),
                track("2", "b.mp3", album = "dune", artist = "frank herbert", number = 2),
                track("x", "Alone.m4b"),
            )
        )
        assertEquals(listOf("Alone", "Dune"), books.map { it.title })
        assertEquals(listOf("1", "2", "3"), books[1].parts.map { it.uri })
        assertEquals("Frank Herbert", books[1].author)
        assertEquals(180_000L, books[1].durationMs)
    }

    @Test
    fun progressCountsEarlierPartsAndWhereThisOneWasLeft() {
        val book = audiobooks(listOf(1, 2, 3).map { track("$it", "$it.mp3", album = "B", number = it) }).single()
        val nothing = bookProgress(book, null, { null }, { false })
        assertEquals(0L, nothing.listenedMs)
        assertEquals(180_000L, nothing.remainingMs)

        val middle = bookProgress(book, "2", { if (it == "2") 30_000 else null }, { false })
        assertEquals(1, middle.partIndex)
        assertEquals(90_000L, middle.listenedMs)
        assertEquals(0.5f, middle.fraction, 0.001f)

        val done = bookProgress(book, "3", { null }, { it == "3" })
        assertEquals(0L, done.remainingMs)
    }

    private val chapters = listOf(Chapter("One", 0), Chapter("Two", 60_000), Chapter("Three", 120_000))

    @Test
    fun chapterNavigation() {
        assertEquals(1, currentChapterIndex(chapters, 61_000))
        assertEquals(-1, currentChapterIndex(emptyList(), 5_000))
        // Just into a chapter: back to the one before; further in: its own start.
        assertEquals(0L, previousChapterStart(chapters, 61_000))
        assertEquals(60_000L, previousChapterStart(chapters, 70_000))
        assertNull(previousChapterStart(chapters, 1_000))
        assertEquals(120_000L, nextChapterStart(chapters, 61_000))
        assertNull(nextChapterStart(chapters, 130_000))
    }

    @Test
    fun bookmarksSurviveSavingAndLoading() {
        val data = AudiobookData(
            bookmarks = listOf(Bookmark("1", "content://a", 61_000, "The duel", 5), Bookmark("2", "content://b", 0, "", 6)),
            lastPart = mapOf("dune|frank herbert" to "content://b"),
        )
        assertEquals(data, parseAudiobookData(writeAudiobookData(data)))
        assertEquals(AudiobookData(), parseAudiobookData("{}"))
    }
}
