package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ListeningStatsTest {

    private val zone = ZoneId.of("Asia/Kuala_Lumpur")

    private fun at(year: Int, month: Int, day: Int = 1, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun play(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        time: Long = at(2026, 10),
        listened: Long = 60_000,
        kind: PlayKind = PlayKind.Music,
    ) = PlayEntry("content://t/$title", title, artist, album, time, listened, kind)

    // --- Counting a play ---

    @Test fun countsAfterThirtySecondsOrHalfTheSong() {
        assertEquals(30_000L, playCountsAfterMs(240_000))
        assertEquals(20_000L, playCountsAfterMs(40_000))
        assertEquals(30_000L, playCountsAfterMs(0))
    }

    @Test fun trackerLogsOnlyCountedPlays() {
        val tracker = ListenTracker()
        assertNull(tracker.start(play("A"), lengthMs = 200_000))
        repeat(29) { tracker.listened(1_000) }
        assertNull(tracker.counted)
        // Skipped after 29 s: not a play.
        assertNull(tracker.start(play("B"), lengthMs = 200_000))
        repeat(31) { tracker.listened(1_000) }
        val finished = tracker.start(play("C"), lengthMs = 200_000)
        assertEquals("B", finished?.title)
        assertEquals(31_000L, finished?.listenedMs)
    }

    @Test fun trackerIgnoresClockJumps() {
        val tracker = ListenTracker()
        tracker.start(play("A"), lengthMs = 200_000)
        tracker.listened(3_600_000)
        tracker.listened(-5_000)
        assertNull(tracker.counted) // capped at 1 s per step
    }

    @Test fun shortSongCountsAtHalf() {
        val tracker = ListenTracker()
        tracker.start(play("Short"), lengthMs = 20_000)
        repeat(10) { tracker.listened(1_000) }
        assertEquals("Short", tracker.finish()?.title)
    }

    // --- The log file ---

    @Test fun jsonLineRoundTrip() {
        val entry = play("Title \"quoted\"", album = "", kind = PlayKind.Podcast)
        assertEquals(entry, parsePlayEntry(entry.toJsonLine()))
        assertNull(parsePlayEntry("{\"u\":\"x\",\"t\":"))
    }

    // --- Periods ---

    @Test fun periods() {
        assertTrue(StatsPeriod.Month(2026, 10).contains(at(2026, 10, 31, 23), zone))
        assertTrue(!StatsPeriod.Month(2026, 10).contains(at(2026, 11, 1, 0), zone))
        assertEquals(StatsPeriod.Month(2027, 1), StatsPeriod.Month(2026, 12).shifted(1))
        assertEquals(StatsPeriod.Month(2025, 12), StatsPeriod.Month(2026, 1).shifted(-1))
        assertEquals(StatsPeriod.Year(2025), StatsPeriod.Year(2026).shifted(-1))
        assertEquals(StatsPeriod.Month(2026, 10), monthOf(at(2026, 10, 15), zone))
    }

    // --- Adding up ---

    @Test fun totalsAndPodcastsSeparately() {
        val stats = listeningStats(
            listOf(play("A"), play("B", listened = 120_000), play("Ep", kind = PlayKind.Podcast, listened = 1_800_000)),
            StatsPeriod.AllTime, zone, { null },
        )
        assertEquals(2, stats.plays)
        assertEquals(180_000L, stats.listenedMs)
        assertEquals(1, stats.podcastPlays)
        assertEquals(1_800_000L, stats.podcastMs)
        assertTrue(stats.songs.none { it.name == "Ep" })
    }

    @Test fun onlyThePeriod() {
        val entries = listOf(play("Oct", time = at(2026, 10)), play("Sep", time = at(2026, 9)), play("Last year", time = at(2025, 10)))
        assertEquals(listOf("Oct"), listeningStats(entries, StatsPeriod.Month(2026, 10), zone, { null }).songs.map { it.name })
        assertEquals(2, listeningStats(entries, StatsPeriod.Year(2026), zone, { null }).plays)
        assertEquals(3, listeningStats(entries, StatsPeriod.AllTime, zone, { null }).plays)
    }

    @Test fun rankedByPlaysThenTime() {
        val entries = listOf(
            play("A", artist = "X"), play("A", artist = "X"),
            play("B", artist = "y", listened = 300_000),
            play("C", artist = "Y", listened = 10_000),
        )
        val stats = listeningStats(entries, StatsPeriod.AllTime, zone, { null })
        assertEquals(listOf("A", "B", "C"), stats.songs.map { it.name })
        assertEquals(2, stats.songs[0].plays)
        // "y" and "Y" are one artist, with 2 plays and more time than X.
        assertEquals(2, stats.artists[0].plays)
        assertEquals(310_000L, stats.artists[0].listenedMs)
        assertEquals("X", stats.artists[1].name)
    }

    @Test fun emptyNamesLeftOutOfTheirList() {
        val stats = listeningStats(listOf(play("A", artist = "", album = " ")), StatsPeriod.AllTime, zone, { null })
        assertEquals(1, stats.plays)
        assertTrue(stats.artists.isEmpty())
        assertTrue(stats.albums.isEmpty())
    }

    @Test fun genresFromTheLookup() {
        val entries = listOf(play("A"), play("B"), play("C"))
        val genre = mapOf("A" to "Rock", "B" to "rock", "C" to null)
        val stats = listeningStats(entries, StatsPeriod.AllTime, zone, { genre[it.title] })
        assertEquals(listOf(RankedItem("Rock", "", 2, 120_000)), stats.genres)
    }

    @Test fun topListsAreCut() {
        val entries = (1..15).map { play("Song $it") }
        assertEquals(TOP_COUNT, listeningStats(entries, StatsPeriod.AllTime, zone, { null }).songs.size)
        assertEquals(3, listeningStats(entries, StatsPeriod.AllTime, zone, { null }, top = 3).songs.size)
    }

    @Test fun minutesUnderAnHour() {
        assertEquals(ListenedAmount(2.0, inHours = false), listenedAmount(150_000))
        assertEquals(ListenedAmount(59.0, inHours = false), listenedAmount(3_599_999))
        assertEquals(ListenedAmount(1.5, inHours = true), listenedAmount(5_400_000))
    }

    @Test fun latestTagsNameTheSong() {
        val old = play("Old title", time = at(2026, 1))
        val new = old.copy(title = "New title", startedAtMs = at(2026, 5))
        assertEquals("New title", listeningStats(listOf(old, new), StatsPeriod.AllTime, zone, { null }).songs.single().name)
    }
}
