package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastFeedsTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>The Show &amp; Friends</title>
            <itunes:author>Host Name</itunes:author>
            <image><url>https://example.com/rss-art.jpg</url><title>Not the show title</title></image>
            <itunes:image href="https://example.com/art.jpg"/>
            <item>
              <title>Episode 1</title>
              <guid isPermaLink="false">ep-1</guid>
              <pubDate>Mon, 20 Oct 2025 07:00:00 GMT</pubDate>
              <itunes:duration>59:30</itunes:duration>
              <enclosure url="https://cdn.example.com/ep1.mp3?token=1" type="audio/mpeg" length="1234"/>
            </item>
            <item>
              <title>Episode 2</title>
              <guid>ep-2</guid>
              <pubDate>Tue, 21 Oct 2025 07:00:00 +0800</pubDate>
              <itunes:duration>3600</itunes:duration>
              <itunes:image href="https://example.com/ep2.jpg"/>
              <enclosure url="https://cdn.example.com/ep2.m4a" type="audio/x-m4a"/>
            </item>
            <item>
              <title>Video only</title>
              <enclosure url="https://cdn.example.com/v.mp4" type="video/mp4"/>
            </item>
            <item>
              <title>No guid</title>
              <enclosure url="https://cdn.example.com/x.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun parse(xml: String = feed, limit: Int = MAX_SAVED_EPISODES) = parseFeed(xml.byteInputStream(), limit)

    @Test fun channel() {
        val parsed = parse()
        assertEquals("The Show & Friends", parsed.title)
        assertEquals("Host Name", parsed.author)
        // <image><url> comes first in this feed.
        assertEquals("https://example.com/rss-art.jpg", parsed.artworkUrl)
    }

    @Test fun episodesNewestFirstAudioOnly() {
        val episodes = parse().episodes
        assertEquals(listOf("Episode 2", "Episode 1", "No guid"), episodes.map { it.title })
        val ep2 = episodes[0]
        assertEquals("ep-2", ep2.guid)
        assertEquals(3_600_000L, ep2.durationMs)
        assertEquals("https://example.com/ep2.jpg", ep2.artworkUrl)
        assertEquals(1234L, episodes[1].sizeBytes)
        assertEquals(3_570_000L, episodes[1].durationMs)
        // Without a guid, the audio URL tells it apart.
        assertEquals("https://cdn.example.com/x.mp3", episodes[2].guid)
    }

    @Test fun limit() = assertEquals(1, parse(limit = 1).episodes.size)

    @Test fun repeatedGuidKeepsTheNewest() {
        val twice = feed.replace("<guid>ep-2</guid>", "<guid isPermaLink=\"false\">ep-1</guid>")
        val episodes = parse(twice).episodes
        assertEquals(listOf("Episode 2", "No guid"), episodes.map { it.title })
    }

    @Test fun searchListsEachFeedOnce() {
        val json = """{"results":[
            {"collectionName":"A","feedUrl":"https://a.example/feed"},
            {"collectionName":"A again","feedUrl":"https://a.example/feed"}]}"""
        assertEquals(listOf("A"), parseItunesSearch(json).map { it.title })
    }

    @Test fun dates() {
        assertEquals(1_760_943_600_000L, parseRfc822("Mon, 20 Oct 2025 07:00:00 GMT"))
        assertEquals(1_760_943_600_000L, parseRfc822("Mon, 20 Oct 2025 15:00:00 +0800"))
        assertEquals(1_760_943_600_000L, parseRfc822("20 Oct 2025 07:00:00 +0000"))
        assertNull(parseRfc822("yesterday"))
        assertNull(parseRfc822(""))
    }

    @Test fun durations() {
        assertEquals(90_000L, parseItunesDuration("90"))
        assertEquals(90_000L, parseItunesDuration("1:30"))
        assertEquals(3_723_000L, parseItunesDuration("1:02:03"))
        assertEquals(0L, parseItunesDuration("long"))
        assertEquals(0L, parseItunesDuration(""))
    }

    @Test fun refusesExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        assertTrue(runCatching { parse(evil) }.isFailure)
    }

    @Test fun search() {
        val json = """
            {"resultCount":3,"results":[
              {"collectionName":"Show A","artistName":"Ann","feedUrl":"https://a.example/feed","artworkUrl600":"https://a.example/600.jpg"},
              {"collectionName":"No feed","artistName":"Bob"},
              {"trackName":"Show C","artistName":"Cat","feedUrl":"https://c.example/rss","artworkUrl100":"https://c.example/100.jpg"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                PodcastSearchResult("Show A", "Ann", "https://a.example/feed", "https://a.example/600.jpg"),
                PodcastSearchResult("Show C", "Cat", "https://c.example/rss", "https://c.example/100.jpg"),
            ),
            parseItunesSearch(json),
        )
        assertTrue(itunesSearchUrl(" lo-fi & chill ").endsWith("term=lo-fi+%26+chill"))
    }

    @Test fun fileNames() {
        val episode = PodcastEpisode("g", "Ep 5: \"Why?\" / Part 1", "https://cdn.example/a/b.m4a?x=1", "audio/mpeg", 0, 0)
        assertEquals("My Show/Ep 5 Why Part 1.m4a", episodeRelativePath("My Show", episode).replace("  ", " "))
        assertEquals("mp3", audioExtension(episode.copy(audioUrl = "https://x/stream", mimeType = "audio/mpeg")))
        assertEquals("m4a", audioExtension(episode.copy(audioUrl = "https://x/stream", mimeType = "audio/x-m4a")))
        assertEquals("Podcast/Episode.mp3", episodeRelativePath("..", episode.copy(title = "", audioUrl = "https://x/y")))
        assertEquals("Hidden", safeFileName("..Hidden"))
    }

    @Test fun newEpisodes() {
        val a = PodcastEpisode("a", "A", "https://x/a.mp3", "audio/mpeg", 1, 0)
        val b = a.copy(guid = "b", title = "B")
        assertEquals(setOf("b"), newEpisodeGuids(listOf(a), listOf(b, a)))
        assertTrue(newEpisodeGuids(listOf(a, b), listOf(a)).isEmpty())
    }

    @Test fun subscriptionsRoundTrip() {
        val subs = listOf(
            PodcastSubscription(
                feedUrl = "https://a.example/feed", title = "Show A", author = "Ann", artworkUrl = null,
                episodes = parse().episodes, newGuids = setOf("ep-2"), lastCheckedMs = 42,
            ),
        )
        assertEquals(subs, subscriptionsFromJson(subscriptionsToJson(subs)))
    }
}
