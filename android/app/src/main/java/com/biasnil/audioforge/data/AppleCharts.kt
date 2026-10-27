package com.biasnil.audioforge.data

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Apple's public charts (no account or key): Top Podcasts, and the music charts in the Charts tab. */
enum class ChartKind(val path: String) {
    Podcasts("podcasts/top/50/podcasts"),
    Songs("music/most-played/50/songs"),
    Albums("music/most-played/50/albums"),
}

/** One place in a chart; [id] is Apple's, so a song moving up or down is still the same entry. */
data class ChartEntry(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /** Its Apple Music / Apple Podcasts page. */
    val url: String?,
    val explicit: Boolean = false,
)

/** A chart as last loaded, with when (only successful loads are kept). */
data class CachedChart(val kind: ChartKind, val country: String, val entries: List<ChartEntry>, val fetchedMs: Long)

/** A followed chart: what's been seen so far, and what was new at the last check. */
data class ChartFollow(val seen: Set<String>, val newIds: Set<String> = emptySet())

/** How opening a chart went. */
enum class ChartLoad { Fresh, Updated, Failed, Unsupported }

fun appleChartUrl(kind: ChartKind, country: String): String =
    "https://rss.marketingtools.apple.com/api/v2/${country.lowercase(Locale.ROOT)}/${kind.path}.json"

fun itunesLookupUrl(appleId: String): String = "https://itunes.apple.com/lookup?entity=podcast&id=" + appleId.filter(Char::isDigit)

/** Apple's chart JSON -> entries; ones without an id or a name are skipped, each id once. */
fun parseAppleChart(json: String): List<ChartEntry> {
    val results = JSONObject(json).optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { i ->
        val obj = results.optJSONObject(i) ?: return@mapNotNull null
        val id = obj.optString("id").ifEmpty { return@mapNotNull null }
        val name = obj.optString("name").ifEmpty { return@mapNotNull null }
        ChartEntry(
            id = id,
            title = name,
            artist = obj.optString("artistName"),
            // 100 px is blurry in a 56 dp row; Apple serves any size from the same address.
            artworkUrl = obj.optString("artworkUrl100").ifEmpty { null }?.replace("/100x100bb.", "/200x200bb."),
            url = obj.optString("url").takeIf { it.startsWith("https://") },
            // Apple spells it "Explict" in some feeds.
            explicit = obj.optString("contentAdvisoryRating").startsWith("Expl", ignoreCase = true),
        )
    }.distinctBy { it.id }
}

/** The RSS feed of a podcast from Apple's lookup, if it has a public one. */
fun parseItunesLookupFeed(json: String): String? {
    val results = JSONObject(json).optJSONArray("results") ?: return null
    return (0 until results.length()).firstNotNullOfOrNull { i ->
        results.optJSONObject(i)?.optString("feedUrl")?.takeIf { it.startsWith("http") }
    }
}

/** The phone's country for the charts ("us" when it has none). */
fun defaultChartCountry(): String = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"

/**
 * Apple's charts, cached per chart and country, refreshed at most once a
 * local calendar day -- and only when a chart is opened: there is no
 * background job. Today's successful load is shown as it is; an older one is
 * shown while one refresh runs; a failed refresh keeps the old chart and
 * doesn't count, so the next visit tries again. Two screens opening the
 * same chart share one request.
 *
 * Followed charts remember the entries already seen; a refresh marks the
 * rest as new (moving up or down the chart isn't new).
 */
class AppleChartRepository(
    private val file: File?,
    private val scope: CoroutineScope,
    private val download: suspend (String) -> String = { url ->
        withContext(Dispatchers.IO) { PodcastRepository.get(url, timeoutMs = TIMEOUT_MS) { it.readBytes().decodeToString() } }
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String, Throwable?) -> Unit = { message, e -> Log.w(TAG, message, e) },
) {
    private val _charts = MutableStateFlow<Map<String, CachedChart>>(emptyMap())
    val charts: StateFlow<Map<String, CachedChart>> = _charts.asStateFlow()

    private val _follows = MutableStateFlow<Map<String, ChartFollow>>(emptyMap())
    /** By [key]. */
    val follows: StateFlow<Map<String, ChartFollow>> = _follows.asStateFlow()

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    /** Chart keys being fetched now. */
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val requests = HashMap<String, Deferred<ChartLoad>>()
    private val requestLock = Mutex()
    private val writeLock = Mutex()
    /** Apple podcast id -> RSS feed, found once per launch. */
    private val feedUrls = HashMap<String, String>()

    init {
        // Off the main thread: nothing is read at startup until a chart is opened.
        scope.launch(Dispatchers.IO) {
            try {
                read()
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    fun key(kind: ChartKind, country: String) = kind.name + ":" + country.lowercase(Locale.ROOT)

    fun chart(kind: ChartKind, country: String): CachedChart? = _charts.value[key(kind, country)]

    /** Whether [chart] was loaded today, in the phone's time zone now. */
    fun isFromToday(chart: CachedChart?): Boolean =
        chart != null && day(chart.fetchedMs) == day(now())

    /** The chart for today: no request if today's is cached; otherwise one (shared) refresh. */
    suspend fun open(kind: ChartKind, country: String, force: Boolean = false): ChartLoad {
        loaded.await()
        val key = key(kind, country)
        val request = requestLock.withLock {
            if (!force && isFromToday(_charts.value[key])) return ChartLoad.Fresh
            requests[key]?.takeIf { it.isActive } ?: scope.async { refresh(kind, country, key) }.also { requests[key] = it }
        }
        return request.await()
    }

    private suspend fun refresh(kind: ChartKind, country: String, key: String): ChartLoad {
        _loading.update { it + key }
        try {
            val entries = try {
                parseAppleChart(download(appleChartUrl(kind, country)))
            } catch (e: IOException) {
                log("Couldn't load $key: ${e.message}", null)
                // Apple answers 404 for a country it has no such chart for.
                return if (e.message.orEmpty().startsWith("HTTP 404")) ChartLoad.Unsupported else ChartLoad.Failed
            } catch (e: Exception) {
                log("Couldn't read $key", e)
                return ChartLoad.Failed
            }
            if (entries.isEmpty()) return ChartLoad.Unsupported
            // Saved first, so today counts only once it's really kept.
            _charts.update { it + (key to CachedChart(kind, country.lowercase(Locale.ROOT), entries, now())) }
            _follows.update { follows ->
                val follow = follows[key] ?: return@update follows
                val ids = entries.map { it.id }
                follows + (key to ChartFollow(seen = follow.seen + ids, newIds = ids.filterNot { it in follow.seen }.toSet()))
            }
            save()
            return ChartLoad.Updated
        } finally {
            _loading.update { it - key }
        }
    }

    fun isFollowed(kind: ChartKind, country: String) = key(kind, country) in _follows.value

    /** Starts following: what's in the chart now isn't "new". */
    fun follow(kind: ChartKind, country: String) {
        val key = key(kind, country)
        val ids = _charts.value[key]?.entries.orEmpty().map { it.id }.toSet()
        _follows.update { it + (key to ChartFollow(seen = ids)) }
        scope.launch { save() }
    }

    /** Stops following; the chart itself stays cached. */
    fun unfollow(kind: ChartKind, country: String) {
        _follows.update { it - key(kind, country) }
        scope.launch { save() }
    }

    /** A chart podcast's RSS feed (Apple's charts don't include it), or null if it has none. */
    suspend fun podcastFeedUrl(appleId: String): String? {
        synchronized(feedUrls) { feedUrls[appleId] }?.let { return it }
        val feed = parseItunesLookupFeed(download(itunesLookupUrl(appleId))) ?: return null
        synchronized(feedUrls) { feedUrls[appleId] = feed }
        return feed
    }

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    private fun read() {
        val file = file ?: return
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val charts = root.optJSONArray("charts").objects().mapNotNull { obj ->
                val kind = ChartKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: return@mapNotNull null
                CachedChart(
                    kind = kind,
                    country = obj.optString("country"),
                    entries = obj.optJSONArray("entries").objects().map { e ->
                        ChartEntry(
                            id = e.optString("id"),
                            title = e.optString("title"),
                            artist = e.optString("artist"),
                            artworkUrl = e.optString("artwork").ifEmpty { null },
                            url = e.optString("url").ifEmpty { null },
                            explicit = e.optBoolean("explicit", false),
                        )
                    }.filter { it.id.isNotEmpty() },
                    fetchedMs = obj.optLong("fetchedMs", 0),
                )
            }
            val follows = root.optJSONArray("follows").objects().associate { obj ->
                obj.optString("key") to ChartFollow(
                    seen = obj.optJSONArray("seen").strings().toSet(),
                    newIds = obj.optJSONArray("new").strings().toSet(),
                )
            }
            _charts.value = charts.associateBy { key(it.kind, it.country) } + _charts.value
            _follows.value = follows + _follows.value
        } catch (e: Exception) {
            // A damaged cache only means the next visit loads the chart again.
            log("Couldn't read ${file.name}", e)
        }
    }

    private suspend fun save() {
        val file = file ?: return
        val charts = _charts.value.values.toList()
        val follows = _follows.value
        val root = JSONObject()
            .put("version", 1)
            .put("charts", JSONArray(charts.map { chart ->
                JSONObject()
                    .put("kind", chart.kind.name)
                    .put("country", chart.country)
                    .put("fetchedMs", chart.fetchedMs)
                    .put("entries", JSONArray(chart.entries.map { e ->
                        JSONObject()
                            .put("id", e.id)
                            .put("title", e.title)
                            .put("artist", e.artist)
                            .put("artwork", e.artworkUrl.orEmpty())
                            .put("url", e.url.orEmpty())
                            .put("explicit", e.explicit)
                    }))
            }))
            .put("follows", JSONArray(follows.map { (key, follow) ->
                // Seen ids are capped so a chart followed for years doesn't grow without end.
                JSONObject()
                    .put("key", key)
                    .put("seen", JSONArray(follow.seen.toList().takeLast(MAX_SEEN)))
                    .put("new", JSONArray(follow.newIds.toList()))
            }))
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    if (!writeAtomically(file, root.toString())) log("Couldn't replace ${file.name}", null)
                } catch (e: Exception) {
                    log("Couldn't save ${file.name}", e)
                }
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

    companion object {
        private const val TAG = "AppleCharts"
        /** Apple can take ~20 s to build a chart it hasn't served lately (then ~2 s). */
        private const val TIMEOUT_MS = 30_000
        private const val MAX_SEEN = 2_000
    }
}

/** Storefronts with Apple Music and Podcasts charts, offered in Change country (names come from the phone's language). */
val CHART_COUNTRIES: List<String> = listOf(
    "ae", "ar", "at", "au", "be", "br", "ca", "ch", "cl", "co", "cz", "de", "dk", "eg", "es", "fi", "fr", "gb", "gr",
    "hk", "hu", "id", "ie", "il", "in", "it", "jp", "ke", "kr", "mx", "my", "ng", "nl", "no", "nz", "pe", "ph", "pl",
    "pt", "ro", "sa", "se", "sg", "th", "tr", "tw", "ua", "us", "vn", "za",
)
