package com.biasnil.audioforge.data

import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverKeyTest {

    private fun song(uri: String, album: String, folder: String = "Music/") =
        Track(uri = uri, title = uri, album = album, folderKey = folder)

    @Test
    fun everySongKeepsItsOwnCover() {
        // Android names untagged songs' album after their folder: 689 songs in "Music" aren't one album.
        assertNotEquals(coverKey(song("1", "Music")), coverKey(song("2", "Music")))
        assertNotEquals(coverKey(song("1", "Singles")), coverKey(song("2", "Singles")))
        assertNotEquals(coverKey(song("1", "")), coverKey(song("2", "")))
    }
}
