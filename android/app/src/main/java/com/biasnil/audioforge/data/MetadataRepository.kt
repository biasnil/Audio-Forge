package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Settings -> Library -> Find missing covers, while it runs. */
data class CoverSearchProgress(val done: Int, val total: Int, val found: Int)

/**
 * Looks up missing tags and covers (see MetadataLookup.kt): Apple's iTunes
 * Search first, Deezer if Apple has nothing. Covers found for whole albums
 * are saved in files/covers/ and shown in place of the missing art (the
 * music files themselves aren't changed); [CoverArtLoader] reads them.
 */
class MetadataRepository(
    context: Context,
    private val coverArt: CoverArtLoader,
    private val scope: CoroutineScope,
) {
    private val coversDir = File(context.filesDir, COVERS_DIR)
    private var coverJob: Job? = null
    private val _coverProgress = MutableStateFlow<CoverSearchProgress?>(null)
    /** Null when no search is running. */
    val coverProgress: StateFlow<CoverSearchProgress?> = _coverProgress.asStateFlow()

    /**
     * What Apple or Deezer know about this song (title and artist are sent);
     * null if neither has a safe match. Throws IOException if neither could
     * be reached.
     */
    suspend fun findSong(title: String, artist: String): FoundInfo? = withContext(Dispatchers.IO) {
        var reached = false
        for (search in listOf(
            { bestSongMatch(parseItunesResults(fetch(itunesSongSearchUrl(title, artist))), title, artist) },
            { bestSongMatch(parseDeezerResults(fetch(deezerSongSearchUrl(title, artist))), title, artist) },
        )) {
            try {
                val found = search()
                reached = true
                if (found != null) return@withContext found
            } catch (e: Exception) {
                Log.w(TAG, "Lookup failed", e)
            }
        }
        if (!reached) throw IOException("No lookup service could be reached")
        null
    }

    /** An album's cover from Apple, else Deezer (album name and artist are sent). */
    private fun findAlbumCover(album: String, artist: String): String? {
        val apple = runCatching { bestAlbumMatch(parseItunesResults(fetch(itunesAlbumSearchUrl(album, artist))), album, artist) }
        apple.getOrNull()?.coverUrl?.let { return it }
        val deezer = runCatching { bestAlbumMatch(parseDeezerResults(fetch(deezerAlbumSearchUrl(album, artist))), album, artist) }
        return deezer.getOrNull()?.coverUrl
    }

    suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) { PodcastRepository.get(url) { it.readBytes() } }

    /**
     * Goes through [tracks]' albums (by album and artist) and, for each one
     * with no cover at all, looks one up and saves it. One album every few
     * seconds, to stay within what the free services allow.
     */
    fun findMissingCovers(tracks: List<Track>) {
        if (coverJob?.isActive == true) return
        val albums = tracks.filter { it.album.isNotBlank() && !it.isRadio }
            .groupBy { albumCoverKey(it.album, it.artist) }
            .values.toList()
        coverJob = scope.launch {
            var found = 0
            _coverProgress.value = CoverSearchProgress(0, albums.size, 0)
            for ((index, songs) in albums.withIndex()) {
                if (!isActive) break
                val first = songs.first()
                // Cover already there (embedded, folder image or an earlier find)?
                if (coverArt.load(first, CHECK_SIZE_PX) == null) {
                    val url = withContext(Dispatchers.IO) { findAlbumCover(first.album, first.artist) }
                    if (url != null && save(first, url)) {
                        found++
                        songs.forEach { coverArt.invalidate(it.uri) }
                    }
                    delay(REQUEST_SPACING_MS)
                }
                _coverProgress.value = CoverSearchProgress(index + 1, albums.size, found)
            }
            _coverProgress.value = null
        }
    }

    fun stopFindingCovers() {
        coverJob?.cancel()
        _coverProgress.value = null
    }

    private suspend fun save(track: Track, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = PodcastRepository.get(url) { it.readBytes() }
            coversDir.mkdirs()
            val file = coverFile(coversDir, track)
            val temp = File(coversDir, file.name + ".tmp")
            temp.writeBytes(bytes)
            temp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the cover for ${track.album}", e)
            false
        }
    }

    private fun fetch(url: String): String = PodcastRepository.get(url) { it.bufferedReader().readText() }

    companion object {
        private const val TAG = "MetadataRepository"
        const val COVERS_DIR = "covers"
        /** Apple asks for about 20 searches a minute at most. */
        private const val REQUEST_SPACING_MS = 3_000L
        private const val CHECK_SIZE_PX = 64

        /** Where a found cover for [track]'s album is kept (it may not exist). */
        fun coverFile(dir: File, track: Track): File {
            val digest = MessageDigest.getInstance("SHA-1").digest(albumCoverKey(track.album, track.artist).toByteArray())
            return File(dir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
        }
    }
}
