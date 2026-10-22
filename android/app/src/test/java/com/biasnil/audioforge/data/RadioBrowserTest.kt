package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RadioBrowserTest {

    private val stationsJson = """
        [
          {"stationuuid":"a","name":" Jazz FM ","url":"http://jazz.example/pls","url_resolved":"https://jazz.example/stream",
           "homepage":"https://jazz.example","favicon":"https://jazz.example/icon.png","tags":"jazz, smooth jazz,,",
           "countrycode":"gb","codec":"MP3","bitrate":128,"hls":0},
          {"stationuuid":"b","name":"News","url":"https://news.example/live.m3u8","url_resolved":"","favicon":"",
           "tags":"","countrycode":"","codec":"AAC","bitrate":0,"hls":1},
          {"stationuuid":"c","name":"No stream","url":"","url_resolved":""},
          {"stationuuid":"a","name":"Jazz FM again","url_resolved":"https://jazz.example/stream2"}
        ]
    """.trimIndent()

    @Test fun stations() {
        val stations = parseStations(stationsJson)
        assertEquals(listOf("a", "b"), stations.map { it.uuid })
        val jazz = stations[0]
        assertEquals("Jazz FM", jazz.name)
        assertEquals("https://jazz.example/stream", jazz.streamUrl)
        assertEquals(listOf("jazz", "smooth jazz"), jazz.tags)
        assertEquals("GB", jazz.countryCode)
        assertEquals("https://jazz.example/icon.png", jazz.favicon)
        val news = stations[1]
        assertEquals("https://news.example/live.m3u8", news.streamUrl)
        assertTrue(news.hls)
        assertNull(news.favicon)
    }

    @Test fun searchPath() {
        assertEquals(
            "/json/stations/search?name=lo-fi+%26+chill&countrycode=MY&tag=jazz&hidebroken=true&order=name&offset=500&limit=1000",
            stationSearchPath(name = " lo-fi & chill ", countryCode = "MY", tag = "Jazz", offset = 500, limit = RADIO_NEXT_PAGE),
        )
        assertEquals("/json/stations/search?hidebroken=true&order=name&offset=0&limit=500", stationSearchPath())
    }

    @Test fun countries() {
        val json = """[{"name":"MY","stationcount":120},{"name":"","stationcount":3},{"name":"de","stationcount":5000}]"""
        assertEquals(listOf(RadioCountry("MY", 120), RadioCountry("DE", 5000)), parseCountryCodes(json))
        assertEquals("Malaysia", countryName("MY", Locale.ENGLISH))
        assertEquals("Allemagne", countryName("DE", Locale.FRENCH))
        assertEquals("ZZ", countryName("ZZ", Locale.ENGLISH))
    }

    @Test fun countryCodesInBothCasesAreOneCountry() {
        // As Radio Browser returns them (this once crashed the country list: two "BG" rows).
        val json = """[{"name":"BG","stationcount":396},{"name":"bg","stationcount":1},{"name":"MY","stationcount":120}]"""
        assertEquals(listOf(RadioCountry("BG", 397), RadioCountry("MY", 120)), parseCountryCodes(json))
    }

    @Test fun recentIsMostRecentFirstWithoutRepeats() {
        val a = RadioStation("a", "A", "https://a")
        val b = RadioStation("b", "B", "https://b")
        assertEquals(listOf("a", "b"), addRecentStation(listOf(a, b), a).map { it.uuid })
        assertEquals(listOf("b", "a"), addRecentStation(listOf(a), b).map { it.uuid })
        val many = (1..30).map { RadioStation("$it", "$it", "https://$it") }
        assertEquals(MAX_RECENT_STATIONS, many.fold(emptyList<RadioStation>()) { list, s -> addRecentStation(list, s) }.size)
    }

    @Test fun details() {
        val jazz = parseStations(stationsJson)[0]
        assertEquals("United Kingdom  •  Jazz, Smooth jazz  •  MP3 128 kbps", stationDetails(jazz, "United Kingdom"))
        assertEquals("AAC", stationDetails(parseStations(stationsJson)[1], null))
    }

    @Test fun savedListsRoundTrip() {
        val stations = parseStations(stationsJson)
        val (favorites, recent) = radioListsFromJson(radioListsToJson(stations, stations.reversed()))
        assertEquals(stations, favorites)
        assertEquals(stations.reversed(), recent)
    }
}
