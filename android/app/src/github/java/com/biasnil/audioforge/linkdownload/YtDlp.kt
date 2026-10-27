package com.biasnil.audioforge.linkdownload

import android.content.Context
import com.biasnil.audioforge.tags.TagFileEditor
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * One video or song behind a link; [playlistIndex] (1-based) when it came
 * from a playlist. [key] stays the same for this entry whatever is shown or
 * picked (a video listed twice gets two keys), for rows and selections.
 */
data class LinkEntry(
    val url: String,
    val title: String,
    val uploader: String,
    val playlistIndex: Int?,
    val key: String = url,
    /** A small picture from the listing itself, when the site gives one (nothing extra is looked up). */
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    /** Deleted or private: listed (so the numbering stays right) but can't be downloaded. */
    val unavailable: Boolean = false,
)

/** The artist and title a song is saved with, after smart title detection and any edits. */
data class SongTags(val artist: String, val title: String)

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
            // The title last: it's the only field that could hold a tab.
            addOption(
                "--print",
                "%(webpage_url,url|)s\t%(playlist_title|)s\t%(playlist_index|)s\t%(uploader,channel|)s\t%(id|)s" +
                    "\t%(thumbnails.0.url,thumbnail|)s\t%(duration|)s\t%(title|)s",
            )
        }
        val out = YoutubeDL.getInstance().execute(request, LIST_ID, null).out
        var playlistTitle: String? = null
        val seen = HashMap<String, Int>()
        val entries = out.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t', limit = 8)
            fun part(i: Int) = parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }
            part(1)?.let { playlistTitle = it }
            val link = part(0).orEmpty()
            val entryUrl = if (link.startsWith("http")) link else url
            val title = part(7) ?: "?"
            // The video's id (or link), plus how many times it came before: duplicates stay apart.
            val base = part(4) ?: entryUrl
            val occurrence = seen.merge(base, 1, Int::plus)!!
            LinkEntry(
                url = entryUrl,
                title = title,
                uploader = part(3)?.removeSuffix(" - Topic").orEmpty(),
                playlistIndex = part(2)?.toIntOrNull(),
                key = if (occurrence == 1) base else "$base#$occurrence",
                thumbnailUrl = part(5)?.takeIf { it.startsWith("https://") },
                durationSeconds = part(6)?.toDoubleOrNull()?.toInt(),
                unavailable = title in UNAVAILABLE_TITLES,
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
     * one book. With [tags] (smart title detection, reviewed on the page),
     * a song is saved with exactly that artist and title instead.
     */
    fun download(
        info: LinkInfo,
        entry: LinkEntry,
        audiobook: Boolean,
        book: String?,
        workDir: File,
        tags: SongTags? = null,
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
            } else if (tags == null) {
                // Smart title detection off: the cleanup this always did.
                addCommands(listOf("--replace-in-metadata", "title", EXTRAS, ""))
                // "Artist - Title" (most music uploads) -> artist and title.
                addOption("--parse-metadata", "title:(?P<artist>.+?) - (?P<title>.+)")
            }
        }
        YoutubeDL.getInstance().execute(request, DOWNLOAD_ID) { progress, _, line -> onProgress(progress / 100f, line) }
        val file = workDir.listFiles { f -> f.extension.equals("m4a", true) }?.firstOrNull()
            ?: throw IllegalStateException("Download finished but no .m4a file was produced")
        // A tag write that fails still leaves the song (with yt-dlp's own tags) rather than nothing.
        if (tags != null && !audiobook) runCatching { writeTags(file, tags) }
        return file
    }

    /** The reviewed artist and title, written into the file (what the page showed is what's saved). */
    private fun writeTags(file: File, tags: SongTags) {
        val current = TagFileEditor.read(file).values
        val edited = LinkedHashMap(current)
        if (tags.title.isNotBlank()) edited["TITLE"] = tags.title
        edited["ARTIST"] = tags.artist
        TagFileEditor.write(file, current, edited)
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

    /** What yt-dlp lists for playlist entries that are gone. */
    private val UNAVAILABLE_TITLES = setOf("[Deleted video]", "[Private video]", "[Unavailable video]")

    /** "(Official Video)", "[Lyrics]", "【MV】" and the like, with the space before them. */
    private const val EXTRAS =
        """\s*[(\[【](?:[^)\]】]*?)(?i:official|lyric|video|audio|visuali[sz]er|mv|hd|4k|explicit)(?:[^)\]】]*)[)\]】]"""
}
