package com.biasnil.audioforge.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** An episode download, as Android's download manager reports it. */
sealed interface EpisodeDownload {
    /** [totalBytes] is -1 until the server says. */
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : EpisodeDownload
    data object Done : EpisodeDownload
    data object Failed : EpisodeDownload
}

/**
 * Podcast search, subscriptions and downloads (the Podcasts tab).
 *
 * Search goes to Apple's podcast directory, episodes come from each show's
 * public RSS feed; both only when the user searches, opens a show or checks
 * for new episodes (no background checks). Subscriptions are saved in
 * files/podcasts.json. Episodes are downloaded by Android's download manager
 * (its own progress notification; it carries on if the app closes) into the
 * phone's Podcasts folder, byte for byte (the show's ads, tags and artwork
 * stay). Android files them as podcasts, so the library picks them up as
 * episodes with no other changes.
 */
class PodcastRepository(
    context: Context,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    /** Episode guid -> "<download id>|<file name>". */
    private val downloadPrefs = appContext.getSharedPreferences("podcast_downloads", Context.MODE_PRIVATE)

    private val _subscriptions = MutableStateFlow(read())
    val subscriptions: StateFlow<List<PodcastSubscription>> = _subscriptions.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, EpisodeDownload>>(emptyMap())
    /** By episode guid; only episodes downloaded (or downloading) through the app. */
    val downloads: StateFlow<Map<String, EpisodeDownload>> = _downloads.asStateFlow()
    /** Episodes whose download is being set up (redirects followed); polling leaves them alone. */
    private val starting = HashSet<String>()

    init {
        scope.launch { refreshDownloads() }
    }

    // --- Directory and feeds -------------------------------------------------

    /** Searches Apple's podcast directory (sends the search words to Apple). */
    suspend fun search(term: String): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        get(itunesSearchUrl(term)) { it.bufferedReader().readText() }.let(::parseItunesSearch)
    }

    /** Downloads and reads a show's feed. */
    suspend fun fetchFeed(feedUrl: String): PodcastFeed = withContext(Dispatchers.IO) { get(feedUrl) { parseFeed(it) } }

    // --- Subscriptions -------------------------------------------------------

    fun subscription(feedUrl: String): PodcastSubscription? = _subscriptions.value.firstOrNull { it.feedUrl == feedUrl }

    fun subscribe(feedUrl: String, feed: PodcastFeed, fallbackArtwork: String?) {
        if (subscription(feedUrl) != null) return
        update {
            it + PodcastSubscription(
                feedUrl = feedUrl,
                title = feed.title,
                author = feed.author,
                artworkUrl = feed.artworkUrl ?: fallbackArtwork,
                episodes = feed.episodes,
                lastCheckedMs = System.currentTimeMillis(),
            )
        }
    }

    /** Stops following a show. Downloaded episodes stay on the phone. */
    fun unsubscribe(feedUrl: String) = update { list -> list.filterNot { it.feedUrl == feedUrl } }

    /** A subscribed show's page was opened: its episodes aren't new any more. */
    fun markSeen(feedUrl: String) {
        if (subscription(feedUrl)?.newGuids.isNullOrEmpty()) return
        update { list -> list.map { if (it.feedUrl == feedUrl) it.copy(newGuids = emptySet()) else it } }
    }

    /** A feed fetched for a subscribed show (opening its page): keeps the saved episodes current. */
    fun updateFromFeed(feedUrl: String, feed: PodcastFeed) {
        update { list -> list.map { if (it.feedUrl == feedUrl) it.withFeed(feed) else it } }
    }

    /** "Check for new episodes": fetches every subscribed feed. Returns how many episodes are new. */
    suspend fun checkForNewEpisodes(): Int {
        var found = 0
        for (sub in _subscriptions.value) {
            val feed = try {
                fetchFeed(sub.feedUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't check ${sub.feedUrl}", e)
                continue
            }
            found += newEpisodeGuids(sub.episodes, feed.episodes).size
            update { list -> list.map { if (it.feedUrl == sub.feedUrl) it.withFeed(feed) else it } }
        }
        return found
    }

    private fun PodcastSubscription.withFeed(feed: PodcastFeed): PodcastSubscription = copy(
        title = feed.title.ifEmpty { title },
        author = feed.author.ifEmpty { author },
        artworkUrl = feed.artworkUrl ?: artworkUrl,
        episodes = feed.episodes,
        newGuids = (newGuids + newEpisodeGuids(episodes, feed.episodes)).filterTo(LinkedHashSet()) { guid ->
            feed.episodes.any { it.guid == guid }
        },
        lastCheckedMs = System.currentTimeMillis(),
    )

    // --- Downloads -----------------------------------------------------------

    /**
     * Starts downloading [episode] of [showTitle] into Podcasts/<Show>/. False
     * if Android refused. Episode links usually pass through a few tracking
     * redirects, more than the download manager follows (5), so they're
     * followed here first.
     */
    suspend fun download(showTitle: String, episode: PodcastEpisode): Boolean {
        // Shown as started while the redirects are followed.
        val started = EpisodeDownload.Running(0, episode.sizeBytes.takeIf { it > 0 } ?: -1)
        _downloads.update { it + (episode.guid to started) }
        starting += episode.guid
        val url = withContext(Dispatchers.IO) { runCatching { finalUrl(episode.audioUrl) }.getOrDefault(episode.audioUrl) }
        return try {
            val request = DownloadManager.Request(url.toUri())
                .setTitle(episode.title)
                .setDescription(showTitle)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_PODCASTS, episodeRelativePath(showTitle, episode))
                .addRequestHeader("User-Agent", UpdateChecker.USER_AGENT)
            val id = downloadManager.enqueue(request)
            downloadPrefs.edit { putString(episode.guid, "$id|") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start download of ${episode.audioUrl}", e)
            _downloads.update { it - episode.guid }
            false
        } finally {
            starting -= episode.guid
        }
    }

    /**
     * Asks the download manager how the episodes' downloads are doing. When
     * one finishes, the library is rescanned so the episode shows up.
     */
    suspend fun refreshDownloads() {
        val saved = downloadPrefs.all.mapNotNull { (guid, value) ->
            val id = (value as? String)?.substringBefore('|')?.toLongOrNull() ?: return@mapNotNull null
            guid to id
        }
        if (saved.isEmpty() && starting.isEmpty()) return
        val byId = saved.associate { (guid, id) -> id to guid }
        val states = HashMap<String, EpisodeDownload>()
        val finishedNames = HashMap<String, String>()
        withContext(Dispatchers.IO) {
            downloadManager.query(DownloadManager.Query().setFilterById(*byId.keys.toLongArray()))?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val doneColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                while (cursor.moveToNext()) {
                    val guid = byId[cursor.getLong(idColumn)] ?: continue
                    states[guid] = when (cursor.getInt(statusColumn)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            cursor.getString(uriColumn)?.let { finishedNames[guid] = Uri.decode(it.substringAfterLast('/')) }
                            EpisodeDownload.Done
                        }
                        DownloadManager.STATUS_FAILED -> EpisodeDownload.Failed
                        else -> EpisodeDownload.Running(cursor.getLong(doneColumn), cursor.getLong(totalColumn))
                    }
                }
            }
        }
        // Downloads the system has forgotten (cleared from its list) are just files now.
        val known = states.keys
        val newlyDone = states.filter { (guid, state) -> state == EpisodeDownload.Done && _downloads.value[guid] !is EpisodeDownload.Done }
        downloadPrefs.edit {
            for ((guid, id) in saved) {
                when {
                    guid !in known -> remove(guid)
                    finishedNames[guid] != null -> putString(guid, "$id|${finishedNames[guid]}")
                }
            }
        }
        _downloads.value = states + _downloads.value.filterKeys { it in starting }
        if (newlyDone.isNotEmpty()) library.refresh()
    }

    /** The file name a download was saved under (the download manager adds "-1" if the name was taken). */
    fun downloadedFileName(guid: String): String? =
        downloadPrefs.getString(guid, null)?.substringAfter('|', "")?.ifEmpty { null }

    /** The episode's file in the library, if it's on the phone. */
    fun localTrack(showTitle: String, episode: PodcastEpisode, podcasts: List<Track>): Track? {
        val names = setOfNotNull(downloadedFileName(episode.guid), episodeFileName(episode))
        val folder = "Podcasts/${safeFileName(showTitle).ifEmpty { "Podcast" }}/"
        return podcasts.firstOrNull { it.fileName in names && it.folderKey.endsWith(folder) }
    }

    // --- Files and network ---------------------------------------------------

    private fun update(change: (List<PodcastSubscription>) -> List<PodcastSubscription>) {
        _subscriptions.update(change)
        scope.launch(Dispatchers.IO) { writeLock.withLock { write(_subscriptions.value) } }
    }

    private fun read(): List<PodcastSubscription> = try {
        if (file.exists()) subscriptionsFromJson(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read $FILE_NAME", e)
        emptyList()
    }

    private fun write(subscriptions: List<PodcastSubscription>) {
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(subscriptionsToJson(subscriptions))
            if (!temp.renameTo(file)) Log.w(TAG, "Couldn't replace $FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    companion object {
        private const val TAG = "PodcastRepository"
        private const val FILE_NAME = "podcasts.json"
        private const val TIMEOUT_MS = 15_000
        /** Podcast links often chain several analytics redirects. */
        private const val MAX_REDIRECTS = 20

        private fun https(url: String): String = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

        /**
         * Where [url] ends up after its redirects (HEAD requests, nothing
         * downloaded). Stops at a plain-http hop, which the download manager
         * can follow itself (the app doesn't allow cleartext).
         */
        fun finalUrl(url: String): String {
            var current = url
            repeat(MAX_REDIRECTS) {
                if (!current.startsWith("https://")) return current
                val connection = URL(current).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
                    if (connection.responseCode !in 300..399) return current
                    val location = connection.getHeaderField("Location") ?: return current
                    current = URL(URL(current), location).toString()
                } finally {
                    connection.disconnect()
                }
            }
            return current
        }

        /**
         * GETs [url] and hands the body to [read]. Follows redirects, also
         * from http to https (which HttpURLConnection won't by itself). Plain
         * http is tried as https: the app doesn't allow cleartext.
         */
        fun <T> get(url: String, read: (InputStream) -> T): T {
            var current = https(url)
            repeat(MAX_REDIRECTS + 1) {
                val connection = URL(current).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
                    when (val code = connection.responseCode) {
                        in 200..299 -> return connection.inputStream.use(read)
                        in 300..399 -> {
                            val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                            current = https(URL(URL(current), location).toString())
                        }
                        else -> throw IOException("HTTP $code for $current")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects for $url")
        }
    }
}
