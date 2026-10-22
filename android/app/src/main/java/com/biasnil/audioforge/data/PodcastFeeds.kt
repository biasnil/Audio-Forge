package com.biasnil.audioforge.data

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

// Podcast search and subscriptions: Apple's podcast directory (iTunes Search
// API, no key needed) and each show's public RSS feed. Only RSS enclosures --
// never Spotify, YouTube or paid feeds. Plain Kotlin, tested on the JVM
// (PodcastFeedsTest); PodcastRepository does the network and files.

/** A show found by search. */
data class PodcastSearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
)

/** One episode from a show's feed. */
data class PodcastEpisode(
    /** The feed's guid, else the audio URL: what tells episodes apart between checks. */
    val guid: String,
    val title: String,
    val audioUrl: String,
    val mimeType: String,
    /** System time of publication; 0 if the feed doesn't say. */
    val publishedMs: Long,
    /** 0 if the feed doesn't say. */
    val durationMs: Long,
    val sizeBytes: Long = 0,
    val artworkUrl: String? = null,
)

/** A show's feed: newest episode first. */
data class PodcastFeed(
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val episodes: List<PodcastEpisode>,
)

/** A subscribed show, saved with its last-known episodes so they show offline. */
data class PodcastSubscription(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val episodes: List<PodcastEpisode> = emptyList(),
    /** Episodes that appeared at the last "Check for new episodes" and haven't been looked at. */
    val newGuids: Set<String> = emptySet(),
    val lastCheckedMs: Long = 0,
)

/** How many episodes of a show are kept (the newest). */
const val MAX_SAVED_EPISODES = 300

// --- Search ------------------------------------------------------------------

fun itunesSearchUrl(term: String): String =
    "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=" + URLEncoder.encode(term.trim(), "UTF-8")

/** The search results that have a public feed (Apple lists some without one), each feed once. */
fun parseItunesSearch(json: String): List<PodcastSearchResult> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { i ->
        val obj = results.optJSONObject(i) ?: return@mapNotNull null
        val feed = obj.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
        PodcastSearchResult(
            title = obj.optString("collectionName").ifEmpty { obj.optString("trackName") },
            author = obj.optString("artistName"),
            feedUrl = feed,
            artworkUrl = obj.optString("artworkUrl600").ifEmpty { obj.optString("artworkUrl100") }.ifEmpty { null },
        )
    }.distinctBy { it.feedUrl }
}

// --- Feeds -------------------------------------------------------------------

/**
 * Reads an RSS podcast feed, streaming (big shows have thousands of
 * episodes). Items without an audio enclosure are skipped, and a guid used
 * twice (it happens) keeps its newest episode. Newest first, at most [limit].
 */
fun parseFeed(input: InputStream, limit: Int = MAX_SAVED_EPISODES): PodcastFeed {
    val handler = FeedHandler()
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    // No external entities or DTDs: a feed is untrusted input.
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    factory.newSAXParser().parse(InputSource(input), handler)
    return PodcastFeed(
        title = handler.channelTitle.trim(),
        author = handler.channelAuthor.trim(),
        artworkUrl = handler.channelImage,
        episodes = handler.episodes.sortedByDescending { it.publishedMs }.distinctBy { it.guid }.take(limit),
    )
}

private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

private class FeedHandler : DefaultHandler() {
    var channelTitle = ""
    var channelAuthor = ""
    var channelImage: String? = null
    val episodes = ArrayList<PodcastEpisode>()

    private val text = StringBuilder()
    private var inItem = false
    private var inChannelImage = false
    // The item being read.
    private var title = ""
    private var guid = ""
    private var audioUrl = ""
    private var mimeType = ""
    private var size = 0L
    private var pubDate = ""
    private var duration = ""
    private var itemImage: String? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        text.setLength(0)
        val itunes = uri == ITUNES_NS
        when {
            localName == "item" && !itunes -> {
                inItem = true
                title = ""; guid = ""; audioUrl = ""; mimeType = ""; size = 0; pubDate = ""; duration = ""; itemImage = null
            }
            localName == "enclosure" && inItem -> {
                val type = attributes.getValue("type").orEmpty()
                val url = attributes.getValue("url").orEmpty()
                // The first audio enclosure (some feeds add a video or transcript one).
                if (audioUrl.isEmpty() && url.isNotEmpty() && (type.startsWith("audio") || type.isEmpty())) {
                    audioUrl = url
                    mimeType = type.ifEmpty { "audio/mpeg" }
                    size = attributes.getValue("length")?.trim()?.toLongOrNull() ?: 0
                }
            }
            localName == "image" && itunes -> {
                val href = attributes.getValue("href")?.takeIf { it.isNotBlank() }
                if (inItem) itemImage = href else if (channelImage == null) channelImage = href
            }
            localName == "image" && !inItem -> inChannelImage = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val value = text.toString().trim()
        val itunes = uri == ITUNES_NS
        if (inItem) {
            when {
                localName == "item" && !itunes -> {
                    inItem = false
                    if (audioUrl.isNotEmpty()) {
                        episodes += PodcastEpisode(
                            guid = guid.ifEmpty { audioUrl },
                            title = title.ifEmpty { audioUrl.substringAfterLast('/') },
                            audioUrl = audioUrl,
                            mimeType = mimeType,
                            publishedMs = parseRfc822(pubDate) ?: 0,
                            durationMs = parseItunesDuration(duration),
                            sizeBytes = size,
                            artworkUrl = itemImage,
                        )
                    }
                }
                localName == "title" && !itunes -> title = value
                localName == "guid" -> guid = value
                localName == "pubDate" -> pubDate = value
                localName == "duration" && itunes -> duration = value
            }
        } else {
            when {
                localName == "title" && !itunes && !inChannelImage && channelTitle.isEmpty() -> channelTitle = value
                localName == "author" && itunes && channelAuthor.isEmpty() -> channelAuthor = value
                localName == "url" && inChannelImage && channelImage == null -> channelImage = value.ifEmpty { null }
                localName == "image" && !itunes -> inChannelImage = false
            }
        }
        text.setLength(0)
    }
}

/** RSS dates: "Tue, 21 Oct 2026 07:00:00 GMT" (also "+0800", no weekday, one-digit day). Null if unreadable. */
fun parseRfc822(value: String): Long? {
    if (value.isBlank()) return null
    val cleaned = value.trim().replace(Regex("\\s+"), " ")
    for (formatter in RFC822_FORMATS) {
        runCatching { return ZonedDateTime.parse(cleaned, formatter).toInstant().toEpochMilli() }
    }
    return null
}

private val RFC822_FORMATS = listOf(
    DateTimeFormatter.RFC_1123_DATE_TIME,
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm Z", Locale.ENGLISH),
)

/** itunes:duration: "3600", "59:30" or "1:02:03" (seconds, mm:ss, h:mm:ss). 0 if unreadable. */
fun parseItunesDuration(value: String): Long {
    val parts = value.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
    if (parts.isEmpty() || parts.size > 3) return 0
    val seconds = parts.fold(0.0) { total, part -> total * 60 + part }
    return (seconds * 1000).toLong()
}

// --- Downloads ---------------------------------------------------------------

/**
 * Where an episode is saved, under the phone's Podcasts folder:
 * "<Show>/<Episode title>.mp3". Android files everything in that folder as a
 * podcast, so it shows up in the Podcasts tab with no other changes.
 */
fun episodeRelativePath(showTitle: String, episode: PodcastEpisode): String =
    safeFileName(showTitle).ifEmpty { "Podcast" } + "/" + episodeFileName(episode)

fun episodeFileName(episode: PodcastEpisode): String =
    safeFileName(episode.title).ifEmpty { "Episode" }.take(MAX_NAME_LENGTH) + "." + audioExtension(episode)

/** The file's extension, from the URL's path, else the type, else mp3. */
fun audioExtension(episode: PodcastEpisode): String {
    val path = runCatching { URI(episode.audioUrl).path }.getOrNull().orEmpty()
    val fromUrl = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    if (fromUrl in AUDIO_EXTENSIONS) return fromUrl
    return when (episode.mimeType.lowercase()) {
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        else -> "mp3"
    }
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
private const val MAX_NAME_LENGTH = 120

/** Characters no file system minds; trimmed, spaces collapsed, no leading dots. */
fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimStart('.')
        .trim()

/** After a check: the episodes that weren't there before (by guid). */
fun newEpisodeGuids(before: List<PodcastEpisode>, after: List<PodcastEpisode>): Set<String> {
    val known = before.mapTo(HashSet()) { it.guid }
    return after.filter { it.guid !in known }.mapTo(LinkedHashSet()) { it.guid }
}

// --- Saving subscriptions ----------------------------------------------------

fun subscriptionsToJson(subscriptions: List<PodcastSubscription>): String = JSONArray().apply {
    for (sub in subscriptions) {
        put(JSONObject().apply {
            put("feed", sub.feedUrl)
            put("title", sub.title)
            put("author", sub.author)
            sub.artworkUrl?.let { put("art", it) }
            put("checked", sub.lastCheckedMs)
            put("new", JSONArray(sub.newGuids.toList()))
            put("episodes", JSONArray().apply {
                for (e in sub.episodes) {
                    put(JSONObject().apply {
                        put("guid", e.guid)
                        put("title", e.title)
                        put("url", e.audioUrl)
                        put("type", e.mimeType)
                        put("date", e.publishedMs)
                        put("dur", e.durationMs)
                        if (e.sizeBytes > 0) put("size", e.sizeBytes)
                        e.artworkUrl?.let { put("art", it) }
                    })
                }
            })
        })
    }
}.toString()

fun subscriptionsFromJson(json: String): List<PodcastSubscription> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val episodes = obj.optJSONArray("episodes") ?: JSONArray()
        val news = obj.optJSONArray("new") ?: JSONArray()
        PodcastSubscription(
            feedUrl = obj.optString("feed").ifEmpty { return@mapNotNull null },
            title = obj.optString("title"),
            author = obj.optString("author"),
            artworkUrl = obj.optString("art").ifEmpty { null },
            lastCheckedMs = obj.optLong("checked"),
            newGuids = (0 until news.length()).mapTo(LinkedHashSet()) { news.getString(it) },
            episodes = (0 until episodes.length()).mapNotNull { j ->
                val e = episodes.optJSONObject(j) ?: return@mapNotNull null
                PodcastEpisode(
                    guid = e.optString("guid"),
                    title = e.optString("title"),
                    audioUrl = e.optString("url").ifEmpty { return@mapNotNull null },
                    mimeType = e.optString("type"),
                    publishedMs = e.optLong("date"),
                    durationMs = e.optLong("dur"),
                    sizeBytes = e.optLong("size"),
                    artworkUrl = e.optString("art").ifEmpty { null },
                )
            },
        )
    }
}
