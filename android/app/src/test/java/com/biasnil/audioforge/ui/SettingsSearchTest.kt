package com.biasnil.audioforge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun s(id: String, title: String, aliases: List<String> = emptyList(), section: String = "Playback", summary: String = "") =
        SearchableSetting(id, "x", title, section, summary, aliases)

    private val all = listOf(
        s("crossfade", "Crossfade between tracks", listOf("fade", "gap")),
        s("replaygain", "ReplayGain", listOf("volume", "loudness")),
        s("dark", "Dark theme", listOf("dark mode", "night"), section = "Appearance"),
        s("lyrics", "Lyrics on Lock Screen", listOf("karaoke"), section = "Lyrics"),
        s("tabs", "Tab bar", listOf("tabs", "more", "hide"), section = "Tab bar"),
        s("language", "Language", listOf("langue"), section = ""),
    )

    private fun ids(query: String) = searchSettings(all, query).map { it.id }

    @Test
    fun familiarWordsFindTheirSetting() {
        assertEquals("replaygain", ids("volume").first())
        assertEquals("language", ids("language").first())
        assertEquals("lyrics", ids("lyrics").first())
        assertEquals("tabs", ids("tabs").first())
        assertEquals("dark", ids("dark mode").first())
    }

    @Test
    fun titlePrefixRanksAboveAlias() {
        val list = listOf(s("a", "Something", listOf("fade")), s("b", "Fade out sleep timer"))
        assertEquals(listOf("b", "a"), searchSettings(list, "fade").map { it.id })
    }

    @Test
    fun everyWordMustMatch() {
        assertTrue(ids("dark banana").isEmpty())
    }

    @Test
    fun caseAccentsAndPunctuationDoNotMatter() {
        assertEquals("replaygain", ids("REPLAY").first())
        val list = listOf(s("t", "Thème sombre"))
        assertEquals(listOf("t"), searchSettings(list, "theme").map { it.id })
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(ids("   ").isEmpty())
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = settingsCatalogIds()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun noLooseSubstringFromSummaries() {
        val list = listOf(s("x", "Crossfade", summary = "between tracks"))
        // "ween" is inside "between" but no word starts with it.
        assertTrue(searchSettings(list, "ween").isEmpty())
    }
}
