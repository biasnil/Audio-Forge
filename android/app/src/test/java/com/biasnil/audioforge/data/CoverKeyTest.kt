package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverKeyTest {

    private fun song(uri: String, album: String, folder: String = "Music/A") =
        Track(uri = uri, title = uri, album = album, folderKey = folder)

    @Test
    fun songsOfOneAlbumShareACover() {
        assertEquals(coverKey(song("1", "Jazz")), coverKey(song("2", "Jazz")))
        assertEquals(coverKey(song("1", "Jazz")), coverKey(song("2", " jazz ")))
    }

    @Test
    fun differentAlbumsOrFoldersDont() {
        assertNotEquals(coverKey(song("1", "Jazz")), coverKey(song("2", "News of the World")))
        // Two albums that happen to share a name ("Greatest Hits") in different folders.
        assertNotEquals(coverKey(song("1", "Greatest Hits", "Music/Queen")), coverKey(song("2", "Greatest Hits", "Music/ABBA")))
    }

    @Test
    fun songsWithoutAnAlbumKeepTheirOwn() {
        assertNotEquals(coverKey(song("1", "")), coverKey(song("2", "")))
    }
}
