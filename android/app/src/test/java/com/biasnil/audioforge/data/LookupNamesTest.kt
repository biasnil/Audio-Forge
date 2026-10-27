package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupNamesTest {
    private val rules = TitleRules()

    @Test
    fun lyricsVideoFromAChannelIsCleaned() {
        val names = lookupNames("NSYNC - Bye Bye Bye (Lyrics) (Deadpool & Wolverine)", "7clouds", rules)
        assertTrue(names.cleaned)
        assertEquals("NSYNC", names.artist)
        assertEquals("Bye Bye Bye (Deadpool & Wolverine)", names.title)
        // Then without the leftover bracket, then as typed.
        assertEquals("Bye Bye Bye" to "NSYNC", names.searches[1])
        assertEquals("NSYNC - Bye Bye Bye (Lyrics) (Deadpool & Wolverine)" to "7clouds", names.searches.last())
    }

    @Test
    fun emptyArtistIsTakenFromTheTitle() {
        val names = lookupNames("Juice WRLD - Robbery", "", rules)
        assertTrue(names.cleaned)
        assertEquals("Juice WRLD", names.artist)
        assertEquals("Robbery", names.title)
    }

    @Test
    fun onlyBracketExtrasKeepTheArtist() {
        val names = lookupNames("Perfect (Official Video)", "Ed Sheeran", rules)
        assertTrue(names.cleaned)
        assertEquals("Perfect" to "Ed Sheeran", names.title to names.artist)
    }

    @Test
    fun cleanTagsStayAsTheyAre() {
        val names = lookupNames("Perfect", "Ed Sheeran", rules)
        assertFalse(names.cleaned)
        assertEquals(listOf("Perfect" to "Ed Sheeran"), names.searches)
    }

    @Test
    fun versionAfterADashIsNotAnArtist() {
        val names = lookupNames("Bohemian Rhapsody - Remastered 2011", "Queen", rules)
        assertFalse(names.cleaned)
        assertEquals("Bohemian Rhapsody - Remastered 2011" to "Queen", names.searches.first())
    }

    @Test
    fun aGuessedSplitIsOnlyTriedAsASearch() {
        val names = lookupNames("Some Song - Other Part", "Real Artist", rules)
        assertFalse(names.cleaned)
        assertEquals("Some Song - Other Part" to "Real Artist", names.searches.first())
        assertTrue(names.searches.contains("Other Part" to "Some Song"))
    }

    @Test
    fun liveInBracketsStillCleans() {
        val names = lookupNames("Artist - Song (Live) (Lyrics)", "Lyrics Channel", rules)
        assertTrue(names.cleaned)
        assertEquals("Song (Live)" to "Artist", names.title to names.artist)
    }

    @Test
    fun worksWhenSmartTitlesAreOffForDownloads() {
        assertTrue(lookupNames("NSYNC - Bye Bye Bye (Lyrics)", "7clouds", TitleRules(enabled = false)).cleaned)
    }
}
