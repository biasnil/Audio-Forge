package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import com.biasnil.audioforge.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * Internet radio (the Radio tab), from Radio Browser -- the desktop
 * World_Radio's core/radio_browser.py, ported:
 *
 * - A mirror is found by looking up all.api.radio-browser.info (what their
 *   docs ask), else the desktop's list of known mirrors.
 * - A request that fails on one mirror is retried on another, so one stalled
 *   server doesn't sink a long paged load.
 * - Every request says User-Agent: AudioForge/<version> (their request), and
 *   station lists hide broken stations.
 * - Playing a station tells /json/url/<uuid> (their click count).
 *
 * Nothing happens until the user opens the Radio tab or plays a station.
 * Favorites and Recently played are saved in files/radio.json.
 */
class RadioRepository(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private val mirrorLock = Mutex()
    /** Mirrors not yet tried (host names), and the one in use. */
    private var mirrors: ArrayDeque<String> = ArrayDeque()
    private var mirror: String? = null

    private val saved = read()
    private val _favorites = MutableStateFlow(saved.first)
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()
    /** For hearts and stars: uuids of [favorites]. */
    val favoriteIds: StateFlow<Set<String>> =
        _favorites.map { list -> list.mapTo(HashSet()) { it.uuid } }.stateIn(scope, SharingStarted.Eagerly, saved.first.mapTo(HashSet()) { it.uuid })
    private val _recent = MutableStateFlow(saved.second)
    /** Most recent first, at most [MAX_RECENT_STATIONS]. */
    val recent: StateFlow<List<RadioStation>> = _recent.asStateFlow()

    // --- Directory -----------------------------------------------------------

    /** A page of stations by name / country / tag; see [stationSearchPath]. */
    suspend fun search(name: String, countryCode: String, tag: String, offset: Int, limit: Int): List<RadioStation> =
        api(stationSearchPath(name, countryCode, tag, offset, limit), PAGE_TIMEOUT_MS, ::parseStations)

    /** The most played stations right now. */
    suspend fun topStations(): List<RadioStation> = api(topStationsPath(), TIMEOUT_MS, ::parseStations)

    /** Countries with stations, for the country filter. */
    suspend fun countries(): List<RadioCountry> = api(COUNTRY_CODES_PATH, TIMEOUT_MS, ::parseCountryCodes)

    /** A vote for [station] ("I like it"). False if it didn't go through (Radio Browser allows one per 10 minutes). */
    suspend fun vote(station: RadioStation): Boolean =
        runCatching { api(votePath(station.uuid), TIMEOUT_MS) { JSONObject(it).optBoolean("ok") } }.getOrDefault(false)

    // --- Favorites and recent ------------------------------------------------

    fun toggleFavorite(station: RadioStation) {
        _favorites.update { list ->
            if (list.any { it.uuid == station.uuid }) list.filterNot { it.uuid == station.uuid } else list + station
        }
        save()
    }

    /** [station] started playing: Recently played, and Radio Browser's click count (fire and forget). */
    fun played(station: RadioStation) {
        _recent.update { addRecentStation(it, station) }
        save()
        scope.launch {
            try {
                api(clickPath(station.uuid), TIMEOUT_MS) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Click count failed for ${station.uuid}", e)
            }
        }
    }

    // --- Network -------------------------------------------------------------

    /** GET [path] from a mirror; on failure, from the next mirror (up to [MAX_ATTEMPTS] in all). */
    private suspend fun <T> api(path: String, timeoutMs: Int, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) {
            val host = currentMirror() ?: throw IOException("No Radio Browser mirror")
            try {
                return@withContext parse(PodcastRepository.get("https://$host$path", USER_AGENT, timeoutMs) { it.bufferedReader().readText() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Mirror $host failed for $path", e)
                dropMirror(host)
            }
        }
        throw lastError ?: IOException("Radio Browser unreachable")
    }

    private suspend fun currentMirror(): String? = mirrorLock.withLock {
        mirror?.let { return@withLock it }
        if (mirrors.isEmpty()) mirrors = ArrayDeque(discoverMirrors())
        mirror = mirrors.removeFirstOrNull()
        mirror
    }

    private suspend fun dropMirror(host: String) = mirrorLock.withLock {
        if (mirror == host) mirror = null
    }

    /**
     * Radio Browser's mirrors, shuffled to spread the load: all.api.radio-browser.info's
     * addresses, each looked up back to its name (their docs' method), else the known list.
     */
    private fun discoverMirrors(): List<String> {
        val found = runCatching {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .map { it.canonicalHostName }
                .filter { it.endsWith(".api.radio-browser.info") }
                .distinct()
        }.getOrDefault(emptyList())
        return found.ifEmpty { RADIO_BROWSER_MIRRORS }.shuffled()
    }

    // --- File ----------------------------------------------------------------

    private fun save() {
        val favorites = _favorites.value
        val recent = _recent.value
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    if (!writeAtomically(file, radioListsToJson(favorites, recent))) Log.w(TAG, "Couldn't replace $FILE_NAME")
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't save $FILE_NAME", e)
                }
            }
        }
    }

    private fun read(): Pair<List<RadioStation>, List<RadioStation>> = try {
        if (file.exists()) radioListsFromJson(file.readText()) else emptyList<RadioStation>() to emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read $FILE_NAME", e)
        emptyList<RadioStation>() to emptyList()
    }

    private companion object {
        const val TAG = "RadioRepository"
        const val FILE_NAME = "radio.json"
        /** Radio Browser asks apps to name themselves: "appname/appversion". */
        const val USER_AGENT = "AudioForge/${BuildConfig.VERSION_NAME}"
        const val TIMEOUT_MS = 8_000
        /** Big pages take longer (the desktop's page_timeout). */
        const val PAGE_TIMEOUT_MS = 20_000
        const val MAX_ATTEMPTS = 3
    }
}
