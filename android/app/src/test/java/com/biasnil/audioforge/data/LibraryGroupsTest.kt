package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupsTest {

    private fun song(title: String, artist: String, album: String, genre: String = "") =
        Track(uri = "$artist/$album/$title", title = title, artist = artist, album = album, genre = genre)

    @Test
    fun groupsEverythingOnce() {
        val songs = listOf(
            song("b song", "Queen", "Jazz", "Rock"),
            song("A song", "queen", "Jazz", "Rock"),
            song("C song", "ABBA", "Arrival", "Pop"),
            song("d song", "", "", ""),
        )
        val groups = libraryGroups(songs)
        assertTrue(groups.ready)
        assertEquals(listOf("A song", "b song", "C song", "d song"), groups.byTitle.map { it.title })
        // A-Z ignoring case, the unknown ("") group last.
        assertEquals(listOf("Arrival", "Jazz", ""), groups.albums.map { it.name })
        assertEquals(listOf("Pop", "Rock", ""), groups.genres.map { it.name })
        assertEquals(2, groups.albums.first { it.name == "Jazz" }.tracks.size)
    }

    @Test
    fun notReadyBeforeTheFirstResult() {
        assertFalse(LibraryGroups().ready)
    }
}
