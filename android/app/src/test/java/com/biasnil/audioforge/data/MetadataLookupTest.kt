package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLookupTest {

    private val itunesSongs = """
        {"resultCount":2,"results":[
          {"wrapperType":"track","kind":"song","artistName":"Queen","collectionName":"Jazz","trackName":"Don't Stop Me Now (Remastered 2011)",
           "artworkUrl100":"https://is1.example/abc/100x100bb.jpg","releaseDate":"1978-11-10T08:00:00Z","primaryGenreName":"Rock","trackNumber":12},
          {"wrapperType":"track","kind":"song","artistName":"Queen Tribute","collectionName":"Covers","trackName":"Don't Stop Me Now",
           "artworkUrl100":"https://is1.example/x/100x100bb.jpg","releaseDate":"2010-01-01T08:00:00Z","primaryGenreName":"Pop","trackNumber":1}
        ]}
    """.trimIndent()

    private val deezerTracks = """
        {"data":[{"title":"Halo","artist":{"name":"Beyoncé"},"album":{"title":"I Am... Sasha Fierce","cover_big":"https://e.example/big.jpg","cover_xl":"https://e.example/xl.jpg"}}]}
    """.trimIndent()

    private val deezerAlbums = """
        {"data":[{"title":"Discovery","artist":{"name":"Daft Punk"},"cover_xl":"https://e.example/discovery.jpg"}]}
    """.trimIndent()

    @Test
    fun parsesItunesSongs() {
        val first = parseItunesResults(itunesSongs).first()
        assertEquals("Jazz", first.album)
        assertEquals(1978, first.year)
        assertEquals("Rock", first.genre)
        assertEquals(12, first.trackNumber)
        assertEquals("https://is1.example/abc/600x600bb.jpg", first.coverUrl)
        assertEquals("Apple", first.source)
    }

    @Test
    fun parsesDeezerTracksAndAlbums() {
        val track = parseDeezerResults(deezerTracks).single()
        assertEquals("I Am... Sasha Fierce", track.album)
        assertEquals("https://e.example/xl.jpg", track.coverUrl)
        val album = parseDeezerResults(deezerAlbums).single()
        assertEquals("Discovery", album.album)
        assertEquals("Daft Punk", album.albumArtist)
        assertEquals("Deezer", album.source)
    }

    @Test
    fun badJsonGivesNothing() {
        assertTrue(parseItunesResults("{}").isEmpty())
        assertTrue(parseDeezerResults("""{"error":{"code":4}}""").isEmpty())
    }

    @Test
    fun namesAreComparedLoosely() {
        assertEquals("dont stop me now", normalizeName("Don't Stop Me Now (Remastered 2011)"))
        assertEquals("beyonce", normalizeName("Beyoncé"))
        assertEquals("hey jude", normalizeName("Hey Jude - Remastered 2015"))
        assertEquals("simon and garfunkel", normalizeName("Simon & Garfunkel"))
    }

    @Test
    fun picksTheRealSongNotACover() {
        val best = bestSongMatch(parseItunesResults(itunesSongs), "Don't Stop Me Now", "Queen")
        assertEquals("Jazz", best?.album)
        assertEquals("Beyoncé", bestSongMatch(parseDeezerResults(deezerTracks), "Halo", "Beyonce")?.artist)
        assertNull(bestSongMatch(parseItunesResults(itunesSongs), "Bohemian Rhapsody", "Queen"))
        assertNull(bestSongMatch(parseItunesResults(itunesSongs), "Don't Stop Me Now", "ABBA"))
    }

    @Test
    fun albumMatchNeedsACover() {
        assertEquals("https://e.example/discovery.jpg", bestAlbumMatch(parseDeezerResults(deezerAlbums), "Discovery", "Daft Punk")?.coverUrl)
        assertNull(bestAlbumMatch(parseDeezerResults(deezerAlbums), "Homework", "Daft Punk"))
    }

    @Test
    fun onlyEmptyFieldsAreFilled() {
        val fields = listOf("TITLE" to "Don't Stop Me Now", "ARTIST" to "Queen", "ALBUM" to "My Mix", "YEAR" to "", "GENRE" to " ", "TRACK" to "")
        val found = FoundInfo(album = "Jazz", year = 1978, genre = "Rock", trackNumber = 12)
        val (updated, filled) = fillEmptyFields(fields, found)
        assertEquals("My Mix", updated.toMap()["ALBUM"])
        assertEquals("1978", updated.toMap()["YEAR"])
        assertEquals("Rock", updated.toMap()["GENRE"])
        assertEquals("12", updated.toMap()["TRACK"])
        assertEquals(setOf("YEAR", "GENRE", "TRACK"), filled)
    }

    @Test
    fun coverKeysIgnoreCaseAndExtras() {
        assertEquals(albumCoverKey("Discovery", "Daft Punk"), albumCoverKey("DISCOVERY (Remastered)", "daft punk"))
    }

    @Test
    fun folderNamedAlbumsArentRealAlbums() {
        // Android names an untagged song's album after its folder.
        assertFalse(hasRealAlbum(Track(uri = "1", title = "a", album = "Music", folderKey = "external_primary|Music/")))
        assertFalse(hasRealAlbum(Track(uri = "1", title = "a", album = "Unknown")))
        assertFalse(hasRealAlbum(Track(uri = "1", title = "a", album = "")))
        assertTrue(hasRealAlbum(Track(uri = "1", title = "a", album = "Discovery", folderKey = "external_primary|Music/")))
    }

    @Test
    fun songsWithoutARealAlbumGetTheirOwnFoundCover() {
        val a = Track(uri = "1", title = "Happy", artist = "NF", album = "Music", folderKey = "external_primary|Music/")
        val b = Track(uri = "2", title = "Perfect", artist = "Ed Sheeran", album = "Music", folderKey = "external_primary|Music/")
        assertNotEquals(foundCoverKey(a), foundCoverKey(b))
        assertEquals(albumCoverKey("Discovery", "Daft Punk"), foundCoverKey(Track(uri = "3", title = "x", artist = "Daft Punk", album = "Discovery")))
    }

    @Test
    fun downloadedTitlesAreCleanedForSearching() {
        assertEquals("Somebody That I Used To Know", songSearchTitle("Gotye - Somebody That I Used To Know (feat. Kimbra) [Official Music Video]", "Gotye"))
        assertEquals("Perfect", songSearchTitle("Ed Sheeran - Perfect (Official Music Video)", "Ed Sheeran"))
        // A dash that isn't "Artist - " stays.
        assertEquals("Rising - Opening", songSearchTitle("Rising - Opening", "TheJimmyleeboy2"))
        assertEquals("Happy", songSearchTitle("Happy", "NF"))
    }
}
