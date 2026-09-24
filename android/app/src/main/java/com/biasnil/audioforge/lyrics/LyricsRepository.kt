package com.biasnil.audioforge.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.SecretBox
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/**
 * Lyrics for whatever is playing, in the desktop's order: local cache, then
 * LRCLIB, then Musixmatch (only with an API key), then a same-name .lrc/.txt
 * next to the track, then "No lyrics found."
 *
 * LRCLIB results are cached (LRCLIB explicitly allows it); Musixmatch's
 * snippet isn't, since its license doesn't grant that. A track change
 * cancels the previous lookup, so a slow reply can never show lyrics for
 * the wrong song.
 */
class LyricsRepository(
    private val context: Context,
    private val store: AppStore,
    playback: PlaybackManager,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<LyricsState>(LyricsState.None)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val cacheDir = File(context.cacheDir, "lyrics")

    init {
        scope.launch {
            playback.state
                // Title/artist too: after a tag edit the lookup runs again with the new names.
                .distinctUntilChangedBy { state -> state.current?.let { Triple(it.uri, it.title, it.artist) } }
                .collectLatest { playbackState ->
                    val track = playbackState.current
                    if (track == null) {
                        _state.value = LyricsState.None
                    } else {
                        _state.value = LyricsState.Loading
                        _state.value = withContext(Dispatchers.IO) { find(track) }
                    }
                }
        }
    }

    private fun find(track: Track): LyricsState {
        readCache(track)?.let { return it }

        if (track.title.isNotBlank()) {
            fetchFromLrclib(track)?.let { (text, synced) ->
                writeCache(track, text, synced)
                return lyricsStateFor(text, synced)
            }
            fetchFromMusixmatch(track)?.let { return lyricsStateFor(it, maybeSynced = false) }
        }

        track.sidecarLyricsUri?.let { uri ->
            // Tried as synced even for .txt: timed text shows synced, anything else falls back to plain.
            readText(Uri.parse(uri))?.let { text -> return lyricsStateFor(text, maybeSynced = true) }
        }
        return LyricsState.NotFound
    }

    // --- LRCLIB (no key needed) ------------------------------------------

    /**
     * (text, isSynced), or null if nothing usable came back. Tries several
     * searches (see [lrclibQueries]) and prefers a result the same length
     * as the song (see [pickLyrics]).
     */
    private fun fetchFromLrclib(track: Track): Pair<String, Boolean>? {
        for (query in lrclibQueries(track.title, track.artist, track.fileName)) {
            val url = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .apply {
                    query.trackName?.let { appendQueryParameter("track_name", it) }
                    query.artistName?.let { appendQueryParameter("artist_name", it) }
                    query.query?.let { appendQueryParameter("q", it) }
                }
                .build()
                .toString()
            val body = httpGet(url) ?: return null // offline or LRCLIB down: the other searches would fail too
            val candidates = try {
                val results = JSONArray(body)
                (0 until results.length()).mapNotNull { results.optJSONObject(it) }.map { result ->
                    LyricsCandidate(
                        durationSeconds = if (result.isNull("duration")) null else result.optDouble("duration"),
                        synced = result.stringOrEmpty("syncedLyrics"),
                        plain = result.stringOrEmpty("plainLyrics"),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected LRCLIB reply", e)
                continue
            }
            pickLyrics(candidates, track.durationMs)?.let { return it }
        }
        return null
    }

    // --- Musixmatch (needs the user's API key; free keys return a snippet) --

    private fun fetchFromMusixmatch(track: Track): String? {
        val apiKey = SecretBox.decrypt(store.settings.value.musixmatchKeyEncrypted)
        if (apiKey.isBlank()) return null
        return try {
            val searchUrl = Uri.parse("https://api.musixmatch.com/ws/1.1/track.search").buildUpon()
                .appendQueryParameter("q_track", track.title)
                .apply { if (track.artist.isNotBlank()) appendQueryParameter("q_artist", track.artist) }
                .appendQueryParameter("page_size", "1")
                .appendQueryParameter("s_track_rating", "desc")
                .appendQueryParameter("apikey", apiKey)
                .build().toString()
            val searchBody = httpGet(searchUrl) ?: return null
            val trackId = JSONObject(searchBody).optJSONObject("message")?.optJSONObject("body")
                ?.optJSONArray("track_list")?.optJSONObject(0)
                ?.optJSONObject("track")?.optLong("track_id") ?: return null
            if (trackId <= 0) return null

            val lyricsUrl = Uri.parse("https://api.musixmatch.com/ws/1.1/track.lyrics.get").buildUpon()
                .appendQueryParameter("track_id", trackId.toString())
                .appendQueryParameter("apikey", apiKey)
                .build().toString()
            val lyricsBody = httpGet(lyricsUrl) ?: return null
            // Shown as-is, including Musixmatch's own attribution line.
            JSONObject(lyricsBody).optJSONObject("message")?.optJSONObject("body")
                ?.optJSONObject("lyrics")?.stringOrEmpty("lyrics_body")?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected Musixmatch reply", e)
            null
        }
    }

    // --- Cache: cacheDir/lyrics/<md5 of track URI>.lrc|.txt ---------------

    private fun cacheFile(track: Track, synced: Boolean): File {
        val digest = MessageDigest.getInstance("MD5").digest(track.uri.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir, key + if (synced) ".lrc" else ".txt")
    }

    private fun readCache(track: Track): LyricsState? {
        for (synced in listOf(true, false)) {
            val file = cacheFile(track, synced)
            if (file.exists()) {
                val text = try { file.readText() } catch (e: IOException) { continue }
                if (text.isNotBlank()) return lyricsStateFor(text, synced)
            }
        }
        return null
    }

    private fun writeCache(track: Track, text: String, synced: Boolean) {
        try {
            cacheDir.mkdirs()
            cacheFile(track, synced).writeText(text)
        } catch (e: IOException) {
            // No cache next time -- not worth bothering the user about.
            Log.w(TAG, "Couldn't cache lyrics", e)
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun httpGet(url: String): String? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            // LRCLIB asks for an identifying User-Agent, same as the desktop's requests.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            null // offline, timeout, DNS... -- fall through to the next source
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A sidecar file's text. Like the desktop: a BOM wins; otherwise strict
     * UTF-8; otherwise the legacy encoding for the phone's language (see
     * [legacyCharset]) -- e.g. GBK for older Chinese .lrc files.
     */
    private fun readText(uri: Uri): String? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        } catch (e: Exception) {
            return null
        }
        val text = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> decodeStrictUtf8(bytes) ?: String(bytes, legacyCharset())
        }
        return text.replace("\r\n", "\n")
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * The desktop falls back to Windows' system codepage (QString::fromLocal8Bit).
     * Android has no such setting, so this picks the legacy encoding that
     * text files in the phone's language were typically saved in.
     */
    private fun legacyCharset(): Charset {
        val locale = Locale.getDefault()
        val name = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "Big5" else "GB18030"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            else -> "windows-1252"
        }
        return if (Charset.isSupported(name)) Charset.forName(name) else Charsets.ISO_8859_1
    }

    private companion object {
        const val TAG = "LyricsRepository"
        const val USER_AGENT = "AudioForge-Android/0.3 ( https://github.com/biasnil/Audio-Forge )"
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

/** org.json's optString() turns a JSON null into the string "null"; this doesn't. */
private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
