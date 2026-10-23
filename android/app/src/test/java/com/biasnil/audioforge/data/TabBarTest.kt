package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TabBarTest {

    private val all = listOf("Songs", "Albums", "Artists", "Genres", "Playlists", "Equalizer", "Effects", "Settings")
    private val classic = listOf("Songs", "Albums", "Artists", "Genres")

    @Test
    fun notChosenYetKeepsTheOldLayout() {
        assertEquals(classic, effectiveTabBar(all, null, emptySet()))
        // Earlier versions skipped hidden tabs when filling the bar.
        assertEquals(listOf("Songs", "Artists", "Genres", "Playlists"), effectiveTabBar(all, null, setOf("Albums")))
    }

    @Test
    fun theRestGoesToMore() {
        val (bar, more) = splitTabBar(all, classic, hidden = setOf("Playlists"))
        assertEquals(classic, bar)
        assertEquals(listOf("Equalizer", "Effects", "Settings"), more)
    }

    @Test
    fun barKeepsTheUsersOrderAndShowsHiddenTabs() {
        val (bar, more) = splitTabBar(all, listOf("Effects", "Songs"), hidden = setOf("Effects"))
        assertEquals(listOf("Effects", "Songs"), bar)
        assertEquals(listOf("Albums", "Artists", "Genres", "Playlists", "Equalizer", "Settings"), more)
    }

    @Test
    fun unknownDuplicateAndExtraTabsAreDropped() {
        val bar = effectiveTabBar(all, listOf("Songs", "Gone", "Songs", "Albums", "Artists", "Genres", "Effects"), emptySet())
        assertEquals(classic, bar)
    }

    @Test
    fun draggingEffectsIntoAFullBarPushesTheLastOneToMore() {
        val list = tabBarEditList(all, classic, emptySet()).toMutableList()
        assertEquals(classic + listOf(null), list.take(5))
        // Effects dragged to just after Albums.
        list.remove("Effects")
        list.add(2, "Effects")
        assertEquals(listOf("Songs", "Albums", "Effects", "Artists"), tabBarFromEditList(list, classic))
    }

    @Test
    fun theBarNeverEndsUpEmpty() {
        val list = listOf(null) + all
        assertEquals(listOf("Songs"), tabBarFromEditList(list, classic))
    }
}
