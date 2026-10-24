package com.biasnil.audioforge.linkdownload

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** One video or song behind a link; [playlistIndex] (1-based) when it came from a playlist. */
data class LinkEntry(val url: String, val title: String, val uploader: String, val playlistIndex: Int?)

/** What's behind a link: one entry, or a playlist of several. */
data class LinkInfo(val url: String, val playlistTitle: String?, val entries: List<LinkEntry>)

/**
 * youtubedl-android (yt-dlp with its own Python, FFmpeg and QuickJS),
 * adapted from Media Downloader Toolkit's Downloader. Every call blocks:
 * run them on Dispatchers.IO. Downloads are audio only, as M4A (YouTube's
 * own AAC, not re-encoded), with the title, artist and cover inside.
 */
object YtDlp {
    private const val DOWNLOAD_ID = "download"
    private const val LIST_ID = "list"

    @Volatile private var ready = false
    @Volatile private var updateChecked = false

    /** Unpacks Python, yt-dlp and FFmpeg the first time (a few seconds); instant afterwards. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
    }

    /** Lists what's behind [url] without downloading anything (fast, even for big playlists). */
    fun list(url: String): LinkInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            addOption("--print", "%(webpage_url,url|)s\t%(playlist_title|)s\t%(playlist_index|)s\t%(uploader,channel|)s\t%(title|)s")
        }
        val out = YoutubeDL.getInstance().execute(request, LIST_ID, null).out
        var playlistTitle: String? = null
        val entries = out.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t', limit = 5)
            fun part(i: Int) = parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }
            part(1)?.let { playlistTitle = it }
            val link = part(0).orEmpty()
            LinkEntry(
                url = if (link.startsWith("http")) link else url,
                title = part(4) ?: "?",
                uploader = part(3)?.removeSuffix(" - Topic").orEmpty(),
                playlistIndex = part(2)?.toIntOrNull(),
            )
        }
        // A single video lists as itself, with no playlist.
        return LinkInfo(url, if (entries.size > 1) playlistTitle else null, entries)
    }

    /**
     * Downloads [entry] as audio into [workDir] (emptied first) and returns
     * the file. A song gets "Artist - Title" split and "(Official Video)"-
     * style extras taken out of its title. An audiobook keeps the video's
     * chapters; when [book] is a playlist's title, every part is filed under
     * it (album) with its number in the playlist (track), so the parts make
     * one book.
     */
    fun download(
        info: LinkInfo,
        entry: LinkEntry,
        audiobook: Boolean,
        book: String?,
        workDir: File,
        onProgress: (Float, String) -> Unit,
    ): File {
        workDir.deleteRecursively()
        workDir.mkdirs()
        // A playlist item is downloaded through the playlist, so its playlist title and number are known.
        val fromPlaylist = book != null && entry.playlistIndex != null
        val request = YoutubeDLRequest(if (fromPlaylist) info.url else entry.url).apply {
            if (fromPlaylist) {
                addOption("--yes-playlist")
                addOption("--playlist-items", entry.playlistIndex.toString())
            } else {
                addOption("--no-playlist")
            }
            addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")
            addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            addOption("-x")
            addOption("--audio-format", "m4a")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg") // YouTube serves .webp, which players ignore as cover art
            addOption("--windows-filenames") // swaps out characters some phones reject in file names
            // Three-value options go in as plain arguments (addOption takes one value).
            addCommands(listOf("--replace-in-metadata", "uploader,channel,artist", " - Topic$", ""))
            if (audiobook) {
                addOption("--embed-chapters")
                addOption("--parse-metadata", "%(uploader,channel|)s:%(artist)s")
                addOption("--parse-metadata", "%(genre|Audiobook)s:%(genre)s")
                if (fromPlaylist) {
                    addOption("--parse-metadata", "playlist_title:%(album)s")
                    addOption("--parse-metadata", "playlist_index:%(track_number)s")
                } else {
                    addOption("--parse-metadata", "title:%(album)s")
                }
            } else {
                addCommands(listOf("--replace-in-metadata", "title", EXTRAS, ""))
                // "Artist - Title" (most music uploads) -> artist and title.
                addOption("--parse-metadata", "title:(?P<artist>.+?) - (?P<title>.+)")
            }
        }
        YoutubeDL.getInstance().execute(request, DOWNLOAD_ID) { progress, _, line -> onProgress(progress / 100f, line) }
        return workDir.listFiles { f -> f.extension.equals("m4a", true) }?.firstOrNull()
            ?: throw IllegalStateException("Download finished but no .m4a file was produced")
    }

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(LIST_ID)
        YoutubeDL.getInstance().destroyProcessById(DOWNLOAD_ID)
    }

    /** YouTube changes often and old yt-dlp versions stop working, so a newer one is fetched once per launch. */
    fun updateOnce(context: Context) {
        if (updateChecked) return
        updateChecked = true
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) }
    }

    /** "2026.10.21"; null until yt-dlp has been unpacked (the first look-up). */
    fun version(context: Context): String? =
        runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()?.removePrefix("yt-dlp")?.trim()?.ifEmpty { null }

    /** "(Official Video)", "[Lyrics]", "【MV】" and the like, with the space before them. */
    private const val EXTRAS =
        """\s*[(\[【](?:[^)\]】]*?)(?i:official|lyric|video|audio|visuali[sz]er|mv|hd|4k|explicit)(?:[^)\]】]*)[)\]】]"""
}
