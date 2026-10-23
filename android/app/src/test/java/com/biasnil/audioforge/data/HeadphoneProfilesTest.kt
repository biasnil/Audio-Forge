package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeadphoneProfilesTest {

    private val hd650 = "Sennheiser HD 650\toratory1990\tover-ear\t-6.1\tL105,6.4,0.7 P8800,5.1,1.42 H10000,-2.1,0.7"

    @Test
    fun parsesALine() {
        val profile = parseHeadphoneProfile(hd650)!!
        assertEquals("Sennheiser HD 650", profile.name)
        assertEquals("oratory1990", profile.source)
        assertEquals("over-ear", profile.kind)
        assertEquals(-6.1, profile.preampDb, 0.0)
        assertEquals(
            listOf(
                ParametricFilter(ParametricFilter.Type.LowShelf, 105.0, 6.4, 0.7),
                ParametricFilter(ParametricFilter.Type.Peaking, 8800.0, 5.1, 1.42),
                ParametricFilter(ParametricFilter.Type.HighShelf, 10000.0, -2.1, 0.7),
            ),
            profile.filters,
        )
    }

    @Test
    fun skipsCommentsAndBadLines() {
        assertNull(parseHeadphoneProfile("# AutoEq abc, MIT License"))
        assertNull(parseHeadphoneProfile("Only\tthree\tfields"))
        assertNull(parseHeadphoneProfile("Name\tsrc\tkind\tloud\tP100,1,1"))
        assertNull(parseHeadphoneProfile("Name\tsrc\tkind\t-3\tX100,1,1 P0,1,1 P100,1,0"))
    }

    @Test
    fun filtersRoundTrip() {
        val filters = parseHeadphoneProfile(hd650)!!.filters
        assertEquals("L105,6.4,0.7 P8800,5.1,1.42 H10000,-2.1,0.7", encodeFilters(filters))
        assertEquals(filters, decodeFilters(encodeFilters(filters)))
    }

    @Test
    fun searchMatchesEveryWordInAnyOrder() {
        val profiles = listOf(
            parseHeadphoneProfile(hd650)!!,
            parseHeadphoneProfile("Sennheiser HD 600\tcrinacle\tover-ear\t-5\tP100,1,1")!!,
            parseHeadphoneProfile("Sony WH-1000XM4\tRtings\tover-ear\t-4\tP100,1,1")!!,
        )
        assertEquals(listOf("Sennheiser HD 650"), searchHeadphones(profiles, "650 senn", 10).map { it.name })
        assertEquals(2, searchHeadphones(profiles, "  SENNHEISER ", 10).size)
        assertEquals(1, searchHeadphones(profiles, "", 1).size)
        assertTrue(searchHeadphones(profiles, "bose", 10).isEmpty())
    }

    @Test
    fun theBundledListIsComplete() {
        // Unit tests run in the app module's folder.
        val lines = File("src/main/assets/autoeq.tsv").readLines()
        val profiles = lines.mapNotNull(::parseHeadphoneProfile)
        // Every line but the header comment is a usable profile.
        assertEquals(lines.size - 1, profiles.size)
        assertTrue(profiles.size > 5000)
        assertTrue(profiles.all { it.preampDb <= 0 })
        assertTrue(profiles.any { it.name == "Sennheiser HD 650" && it.source == "oratory1990" })
    }
}
