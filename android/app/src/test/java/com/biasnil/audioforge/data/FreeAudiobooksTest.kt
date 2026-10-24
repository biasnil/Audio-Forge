package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeAudiobooksTest {

    @Test
    fun parsesSearchResults() {
        val json = """
            {"response":{"numFound":2,"docs":[
              {"creator":"Jane Austen","identifier":"pride_and_prejudice_librivox","runtime":"13:06:44","title":"Pride and Prejudice"},
              {"creator":["Homer","Samuel Butler"],"identifier":"odyssey_butler_librivox","title":"The Odyssey"},
              {"title":"No id, skipped"}
            ]}}
        """
        val books = parseFreeBookSearch(json)
        assertEquals(2, books.size)
        assertEquals(FreeBook("pride_and_prejudice_librivox", "Pride and Prejudice", "Jane Austen", "13:06:44"), books[0])
        // Several creators: the first is the author.
        assertEquals("Homer", books[1].author)
    }

    @Test
    fun aBooksPartsAreItsSmallMp3sInFileNameOrder() {
        val json = """
            {"metadata":{"title":"Pride and Prejudice","creator":"Jane Austen","runtime":"13:06:44",
               "description":"<a href=\"http://librivox.org/\">Librivox</a> recording of <em>Pride &amp; Prejudice</em>.<br />Read by volunteers."},
             "files":[
              {"name":"pp_02_austen_64kb.mp3","format":"64Kbps MP3","title":"Chapter 2","length":"12:57"},
              {"name":"pp_01_austen_64kb.mp3","format":"64Kbps MP3","title":"Chapter 1","length":"1132.5"},
              {"name":"pp_01_austen.mp3","format":"VBR MP3","title":"Chapter 1"},
              {"name":"__ia_thumb.jpg","format":"Item Tile","source":"original"},
              {"name":"Pride_1104.jpg","format":"JPEG","source":"original"}
            ]}
        """
        val details = parseFreeBookDetails("pp", json)
        assertEquals(listOf("Chapter 1", "Chapter 2"), details.parts.map { it.title })
        assertEquals(1_132_500L, details.parts[0].durationMs)
        assertEquals(777_000L, details.parts[1].durationMs)
        assertEquals("Pride_1104.jpg", details.coverFileName)
        assertEquals("Librivox recording of Pride & Prejudice.\nRead by volunteers.", details.description)
    }

    @Test
    fun partNumbersAreTakenOffTitles() {
        assertEquals("The Raven", partTitle("01 - The Raven"))
        assertEquals("Chapter 6", partTitle("Chapter 6"))
        assertEquals("1984 Reprise", partTitle("1984 Reprise"))
        assertEquals("12", partTitle("12"))
    }

    @Test
    fun lengths() {
        assertEquals(3_723_000L, parseLength("1:02:03"))
        assertEquals(65_000L, parseLength("01:05"))
        assertEquals(0L, parseLength(""))
        assertEquals(0L, parseLength("soon"))
    }

    @Test
    fun filesAndFoldersSortAndAreSafe() {
        assertEquals("Jane Austen - Pride and Prejudice", freeBookFolder(FreeBook("x", "Pride and Prejudice", "Jane Austen")))
        assertEquals("Odyssey", freeBookFolder(FreeBook("x", "Odyssey", "")))
        assertEquals("007 Chapters 1 3.mp3", freeBookPartFileName(6, FreeBookPart("a.mp3", "Chapters 1/3", 0)))
        assertEquals("010 Part 10.mp3", freeBookPartFileName(9, FreeBookPart("a.mp3", "", 0)))
    }

    @Test
    fun urlsAreEncoded() {
        assertEquals("https://archive.org/download/pp/Pride%20Part%201.mp3", freeBookFileUrl("pp", "Pride Part 1.mp3"))
        val search = freeBookSearchUrl("pride \"prejudice\"")
        assertTrue(search, search.startsWith("https://archive.org/advancedsearch.php?q=collection%3Alibrivoxaudio"))
        assertTrue(search, "prejudice" in search && "%22" !in search)
    }
}
