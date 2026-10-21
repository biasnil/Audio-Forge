package com.biasnil.audioforge.data

import android.util.Log
import com.biasnil.audioforge.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** The app's own links (Settings -> About). */
object AboutLinks {
    const val SUPPORT_EMAIL = "workacckwanrong@gmail.com"
    const val REPO_URL = "https://github.com/biasnil/Audio-Forge"
    /** Not /releases/latest: that is the newest *desktop* release (see [newestAndroidRelease]). */
    const val RELEASES_API = "https://api.github.com/repos/biasnil/Audio-Forge/releases?per_page=30"

    /** The release page for an Android version, e.g. .../releases/tag/Androidv1.0.2. */
    fun releasePage(version: String) = "$REPO_URL/releases/tag/$ANDROID_TAG_PREFIX$version"
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** A newer Android release; [url] is its GitHub release page. */
    data class Available(val version: String, val url: String) : UpdateState
    /** Offline, rate-limited, bad JSON: shown quietly. */
    data object Failed : UpdateState
}

/**
 * Asks GitHub for the newest Android release (Settings -> About -> Version).
 * Only reads a version number: the app never downloads or installs
 * anything itself, the user gets the release page in the browser.
 */
class UpdateChecker(private val store: AppStore) {

    private val _state = MutableStateFlow(knownState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Checks now and returns the result (also published in [state]). */
    suspend fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = try {
            val json = withContext(Dispatchers.IO) { fetch() }
            newestAndroidRelease(json, BuildConfig.VERSION_NAME) ?: UpdateState.UpToDate
        } catch (e: CancellationException) {
            _state.value = knownState()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            UpdateState.Failed
        }
        if (result != UpdateState.Failed) {
            store.update {
                it.copy(
                    lastUpdateCheckMs = System.currentTimeMillis(),
                    latestKnownVersion = (result as? UpdateState.Available)?.version.orEmpty(),
                )
            }
        }
        _state.value = result
        return result
    }

    /** The automatic check: at most once every 24 h, and only if switched on. */
    suspend fun checkIfDue() {
        val settings = store.settings.value
        if (!BuildConfig.UPDATE_CHECK_ENABLED || !settings.autoUpdateCheck) return
        if (System.currentTimeMillis() - settings.lastUpdateCheckMs < AUTO_CHECK_INTERVAL_MS) return
        if (_state.value == UpdateState.Checking) return
        check()
    }

    /** What the last successful check found, without asking again. */
    private fun knownState(): UpdateState {
        val known = store.settings.value.latestKnownVersion
        val newer = known.isNotEmpty() && compareVersions(known, BuildConfig.VERSION_NAME)?.let { it > 0 } == true
        return if (newer) UpdateState.Available(known, AboutLinks.releasePage(known)) else UpdateState.Idle
    }

    private fun fetch(): String {
        val connection = URL(AboutLinks.RELEASES_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("GitHub replied $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val TIMEOUT_MS = 10_000
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** Also used for the lyrics services. */
        const val USER_AGENT = "AudioForge-Android/${BuildConfig.VERSION_NAME} ( ${AboutLinks.REPO_URL} )"
    }
}

/** Android releases are tagged "Androidv<versionName>"; the desktop's are plain "1.3.0". */
const val ANDROID_TAG_PREFIX = "Androidv"

/**
 * The newest published Android release in GitHub's release list [json], if
 * it's newer than [current]. Desktop releases, drafts, prereleases and tags
 * that aren't a version are skipped. Throws on JSON that isn't a list.
 */
fun newestAndroidRelease(json: String, current: String): UpdateState.Available? {
    val releases = JSONArray(json)
    var best: Pair<List<Int>, UpdateState.Available>? = null
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val tag = release.optString("tag_name")
        if (!tag.startsWith(ANDROID_TAG_PREFIX, ignoreCase = true)) continue
        val versionText = tag.substring(ANDROID_TAG_PREFIX.length)
        val version = parseVersion(versionText) ?: continue
        if (best == null || compareParts(version, best.first) > 0) {
            val url = release.optString("html_url").ifEmpty { AboutLinks.releasePage(versionText) }
            best = version to UpdateState.Available(versionText, url)
        }
    }
    val newest = best ?: return null
    val installed = parseVersion(current) ?: return newest.second
    return newest.second.takeIf { compareParts(newest.first, installed) > 0 }
}

/** Positive if [a] is newer than [b], 0 if the same, null if either isn't a version. */
fun compareVersions(a: String, b: String): Int? {
    val first = parseVersion(a) ?: return null
    val second = parseVersion(b) ?: return null
    return compareParts(first, second)
}

/** "1.0.10" -> [1, 0, 10]; missing parts are 0 and a trailing letter ("1.1.1a") is ignored. */
private fun parseVersion(text: String): List<Int>? {
    val match = VERSION.matchEntire(text.trim()) ?: return null
    return (1..3).map { match.groupValues[it].ifEmpty { "0" }.toIntOrNull() ?: return null }
}

private fun compareParts(a: List<Int>, b: List<Int>): Int =
    a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0

private val VERSION = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?[A-Za-z]?""")
