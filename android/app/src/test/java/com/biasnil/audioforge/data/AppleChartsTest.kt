package com.biasnil.audioforge.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class AppleChartsTest {
    private fun chartJson(vararg ids: String) =
        """{"feed":{"results":[""" + ids.joinToString(",") {
            """{"id":"$it","name":"Song $it","artistName":"Artist","artworkUrl100":"https://x/100x100bb.jpg","url":"https://music.apple.com/x"}"""
        } + "]}}"

    private class Fixture(var json: String = "") {
        val requests = AtomicInteger()
        var fail: IOException? = null
        var gate: CompletableDeferred<Unit>? = null
        var nowMs = LocalDateTime.of(2026, 10, 28, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        var zone: ZoneId = ZoneOffset.UTC
        val repo = AppleChartRepository(
            file = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            download = { _ ->
                requests.incrementAndGet()
                gate?.await()
                fail?.let { throw it }
                json
            },
            now = { nowMs },
            zone = { zone },
            log = { _, _ -> },
        )
    }

    @Test
    fun parsesEntriesAndBiggerArtwork() {
        val entries = parseAppleChart(chartJson("1", "2", "1"))
        assertEquals(listOf("1", "2"), entries.map { it.id })
        assertEquals("https://x/200x200bb.jpg", entries[0].artworkUrl)
    }

    @Test
    fun lookupFindsFeed() {
        assertEquals("https://feed", parseItunesLookupFeed("""{"results":[{"feedUrl":"https://feed"}]}"""))
        assertNull(parseItunesLookupFeed("""{"results":[{}]}"""))
    }

    @Test
    fun onceADayAtMost() = runBlocking {
        val f = Fixture(chartJson("a", "b"))
        repeat(10) { f.repo.open(ChartKind.Podcasts, "us") }
        assertEquals(1, f.requests.get())
        assertEquals(2, f.repo.chart(ChartKind.Podcasts, "us")!!.entries.size)
    }

    @Test
    fun aNewDayRefreshesOnce() = runBlocking {
        val f = Fixture(chartJson("a"))
        f.repo.open(ChartKind.Podcasts, "us")
        f.nowMs += 24 * 3600_000L
        f.repo.open(ChartKind.Podcasts, "us")
        f.repo.open(ChartKind.Podcasts, "us")
        assertEquals(2, f.requests.get())
    }

    @Test
    fun failureDoesNotCountAndKeepsTheOldChart() = runBlocking {
        val f = Fixture(chartJson("a"))
        f.repo.open(ChartKind.Podcasts, "us")
        f.nowMs += 24 * 3600_000L
        f.fail = IOException("timeout")
        assertEquals(ChartLoad.Failed, f.repo.open(ChartKind.Podcasts, "us"))
        assertEquals(1, f.repo.chart(ChartKind.Podcasts, "us")!!.entries.size)
        f.fail = null
        assertEquals(ChartLoad.Updated, f.repo.open(ChartKind.Podcasts, "us"))
        assertEquals(ChartLoad.Fresh, f.repo.open(ChartKind.Podcasts, "us"))
        assertEquals(3, f.requests.get())
    }

    @Test
    fun simultaneousOpensShareOneRequest() = runBlocking {
        val f = Fixture(chartJson("a"))
        f.gate = CompletableDeferred()
        val one = async(Dispatchers.Default) { f.repo.open(ChartKind.Songs, "gb") }
        val two = async(Dispatchers.Default) { f.repo.open(ChartKind.Songs, "gb") }
        while (f.requests.get() == 0) Thread.sleep(5)
        Thread.sleep(50)
        f.gate!!.complete(Unit)
        one.await(); two.await()
        assertEquals(1, f.requests.get())
    }

    @Test
    fun countriesAreCachedApart() = runBlocking {
        val f = Fixture(chartJson("a"))
        f.repo.open(ChartKind.Songs, "us")
        f.repo.open(ChartKind.Songs, "GB")
        f.repo.open(ChartKind.Songs, "gb")
        assertEquals(2, f.requests.get())
    }

    @Test
    fun missingCountryIsUnsupported() = runBlocking {
        val f = Fixture()
        f.fail = IOException("HTTP 404 for x")
        assertEquals(ChartLoad.Unsupported, f.repo.open(ChartKind.Songs, "zz"))
        assertNull(f.repo.chart(ChartKind.Songs, "zz"))
    }

    @Test
    fun timezoneChangeUsesTheNewLocalDay() = runBlocking {
        val f = Fixture(chartJson("a"))
        // Loaded at 15:00 UTC on the 28th (23:00 in Kuala Lumpur)...
        f.nowMs = LocalDateTime.of(2026, 10, 28, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        f.repo.open(ChartKind.Podcasts, "us")
        // ...two hours later it's still the 28th in UTC: no new request...
        f.nowMs += 2 * 3600_000L
        f.repo.open(ChartKind.Podcasts, "us")
        assertEquals(1, f.requests.get())
        // ...but in Kuala Lumpur it's already the 29th.
        f.zone = ZoneId.of("Asia/Kuala_Lumpur")
        assertFalse(f.repo.isFromToday(f.repo.chart(ChartKind.Podcasts, "us")))
        f.repo.open(ChartKind.Podcasts, "us")
        assertEquals(2, f.requests.get())
    }

    @Test
    fun followMarksOnlyGenuinelyNewEntries() = runBlocking {
        val f = Fixture(chartJson("a", "b"))
        f.repo.open(ChartKind.Songs, "us")
        f.repo.follow(ChartKind.Songs, "us")
        f.nowMs += 24 * 3600_000L
        f.json = chartJson("b", "a", "c") // a and b swapped places: not new
        f.repo.open(ChartKind.Songs, "us")
        assertEquals(setOf("c"), f.repo.follows.value[f.repo.key(ChartKind.Songs, "us")]!!.newIds)
        assertTrue(f.repo.isFollowed(ChartKind.Songs, "us"))
        f.repo.unfollow(ChartKind.Songs, "us")
        assertFalse(f.repo.isFollowed(ChartKind.Songs, "us"))
    }
}
