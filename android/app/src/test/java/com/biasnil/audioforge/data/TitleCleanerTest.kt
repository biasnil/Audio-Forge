package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {
    private val rules = TitleRules()

    private fun suggest(title: String, uploader: String = "", rules: TitleRules = this.rules) =
        TitleCleaner.suggest(title, uploader, rules)

    @Test
    fun artistFirstWithOfficialVideo() {
        val s = suggest("Ed Sheeran - Perfect (Official Music Video)", "Ed Sheeran")
        assertEquals("Ed Sheeran", s.artist)
        assertEquals("Perfect", s.title)
        assertEquals(listOf("Official Music Video"), s.removed)
        assertFalse(s.needsReview)
        assertTrue(s.changed)
    }

    @Test
    fun titleFirstFromUploaderEvidence() {
        val s = suggest("Perfect - Ed Sheeran", "Ed Sheeran")
        assertEquals("Ed Sheeran", s.artist)
        assertEquals("Perfect", s.title)
    }

    @Test
    fun topicChannelCountsAsTheArtist() {
        val s = suggest("Robbery - Juice WRLD", "Juice WRLD - Topic")
        assertEquals("Juice WRLD", s.artist)
        assertEquals("Robbery", s.title)
    }

    @Test
    fun noEvidenceDefaultsToArtistFirst() {
        val s = suggest("Juice WRLD - Robbery", "Some Label")
        assertEquals("Juice WRLD", s.artist)
        assertEquals("Robbery", s.title)
        assertFalse(s.needsReview)
    }

    @Test
    fun hyphenInsideANameIsNotASeparator() {
        val s = suggest("A-ha - Take On Me", "")
        assertEquals("A-ha", s.artist)
        assertEquals("Take On Me", s.title)
    }

    @Test
    fun severalSeparatorsWithoutEvidenceAsksForReview() {
        val s = suggest("Artist - Song - Extended", "")
        assertEquals("Artist", s.artist)
        assertEquals("Song - Extended", s.title)
        assertTrue(s.needsReview)
    }

    @Test
    fun remixFeaturedAndLiveStay() {
        assertEquals("Song (feat. Someone)", suggest("Artist - Song (feat. Someone) [Official Video]").title)
        assertEquals("Song (Remix)", suggest("Artist - Song (Remix)").title)
        assertEquals("Song (Live at Wembley)", suggest("Artist - Song (Live at Wembley)").title)
        assertEquals("Song (Official Video HD)", suggest("Artist - Song (Official Video HD)").title)
    }

    @Test
    fun separatorInsideBracketsIsIgnored() {
        val s = suggest("Song (A - B Remix)", "Uploader")
        assertEquals("Uploader", s.artist)
        assertEquals("Song (A - B Remix)", s.title)
    }

    @Test
    fun allBracketKindsAndCjk() {
        assertEquals("Song", suggest("Artist - Song [Lyrics]").title)
        assertEquals("Song", suggest("Artist - Song {Official Audio}").title)
        assertEquals("歌", suggest("歌手 - 歌【MV】").title)
        assertEquals("歌手", suggest("歌手 - 歌【MV】").artist)
    }

    @Test
    fun caseAndSpacingDoNotMatter() {
        assertEquals("Song", suggest("Artist - Song (  official   VIDEO )").title)
    }

    @Test
    fun unmatchedBracketsKeepTheTitle() {
        val s = suggest("Artist - Song (Official Video", "")
        assertEquals("Song (Official Video", s.title)
        assertTrue(s.needsReview)
    }

    @Test
    fun noSeparatorKeepsTitleAndUsesUploader() {
        val s = suggest("Just A Title (Official Video)", "Channel")
        assertEquals("Channel", s.artist)
        assertEquals("Just A Title", s.title)
    }

    @Test
    fun onlyBracketsLeavesTheOriginal() {
        assertEquals("(Official Video)", suggest("(Official Video)", "X").title)
    }

    @Test
    fun disabledChangesNothing() {
        val s = suggest("Ed Sheeran - Perfect (Official Music Video)", "Ed Sheeran", TitleRules(enabled = false))
        assertEquals("Ed Sheeran - Perfect (Official Music Video)", s.title)
        assertFalse(s.changed)
    }

    @Test
    fun customAndDisabledPhrases() {
        val custom = TitleRules(custom = listOf("Visualiser Clip"), disabled = setOf("Explicit"))
        assertEquals("Song", suggest("A - Song (Visualiser Clip)", rules = custom).title)
        assertEquals("Song (Explicit)", suggest("A - Song (Explicit)", rules = custom).title)
    }

    @Test
    fun forcedTitleFirst() {
        val s = suggest("Song - Artist", "", TitleRules(order = TitleOrder.TitleFirst))
        assertEquals("Artist", s.artist)
        assertEquals("Song", s.title)
    }

    @Test
    fun accentedUploaderMatches() {
        val s = suggest("Canción - Beyoncé", "Beyonce")
        assertEquals("Beyoncé", s.artist)
        assertEquals("Canción", s.title)
    }

    @Test
    fun hugeInputIsLeftAlone() {
        val long = "A - " + "x".repeat(10_000)
        assertEquals(long, suggest(long).title)
    }

    @Test
    fun emptyInput() {
        val s = suggest("", "Up")
        assertEquals("", s.title)
        assertEquals("Up", s.artist)
    }
}
