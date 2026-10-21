package com.biasnil.audioforge.ui

import androidx.compose.runtime.saveable.listSaver

/**
 * Pages shown on top of the library screen, as a simple back stack. Each
 * page encodes to a string so the stack survives rotation and the app being
 * reclaimed in the background; pages look their data up again by name/id,
 * so they always show the current library.
 */
sealed interface Page {
    data object NowPlaying : Page
    data object UpNext : Page
    data object FullLyrics : Page
    data class AlbumDetail(val name: String) : Page
    data class ArtistDetail(val name: String) : Page
    data class GenreDetail(val name: String) : Page
    data class SmartPlaylistDetail(val id: String) : Page
    /** null = a new smart playlist. */
    data class SmartPlaylistEdit(val id: String?) : Page
    data object Duplicates : Page
    data class PlaylistDetail(val id: String) : Page
    data class AddTracks(val playlistId: String) : Page
    data class EditTags(val trackUri: String) : Page
    /** Songs for a wallpaper video: an existing assignment ([entryId]) or a new one ([newVideoUri]). */
    data class WallpaperTracks(val entryId: String?, val newVideoUri: String?) : Page
    // Settings -> About
    data object Terms : Page
    data object Privacy : Page
    data object Acknowledgements : Page
    /** The full text of one licence from the Acknowledgements list. */
    data class LicenseText(val id: String) : Page
    /** Songs for a visualizer GIF / video: an existing item ([entryId]) or a new one ([newUri]). */
    data class MediaTracks(val entryId: String?, val newUri: String?) : Page
    /** One podcast show's episodes. */
    data class PodcastShow(val name: String) : Page
    data object Widgets : Page
    data object ListeningStats : Page

    fun encode(): String = when (this) {
        NowPlaying -> "now"
        UpNext -> "upnext"
        FullLyrics -> "lyrics"
        is AlbumDetail -> "album:$name"
        is ArtistDetail -> "artist:$name"
        is GenreDetail -> "genre:$name"
        is SmartPlaylistDetail -> "smart:$id"
        is SmartPlaylistEdit -> "smartedit:${id.orEmpty()}"
        Duplicates -> "duplicates"
        is PlaylistDetail -> "playlist:$id"
        is AddTracks -> "add:$playlistId"
        is EditTags -> "tags:$trackUri"
        is WallpaperTracks -> if (entryId != null) "wall:$entryId" else "wallnew:${newVideoUri.orEmpty()}"
        Terms -> "terms"
        Privacy -> "privacy"
        Acknowledgements -> "acks"
        is LicenseText -> "license:$id"
        is MediaTracks -> if (entryId != null) "media:$entryId" else "medianew:${newUri.orEmpty()}"
        is PodcastShow -> "show:$name"
        Widgets -> "widgets"
        ListeningStats -> "stats"
    }

    companion object {
        fun decode(value: String): Page? = when {
            value == "now" -> NowPlaying
            value == "upnext" -> UpNext
            value == "lyrics" -> FullLyrics
            value.startsWith("album:") -> AlbumDetail(value.removePrefix("album:"))
            value.startsWith("artist:") -> ArtistDetail(value.removePrefix("artist:"))
            value.startsWith("genre:") -> GenreDetail(value.removePrefix("genre:"))
            value.startsWith("smartedit:") -> SmartPlaylistEdit(value.removePrefix("smartedit:").ifEmpty { null })
            value.startsWith("smart:") -> SmartPlaylistDetail(value.removePrefix("smart:"))
            value == "duplicates" -> Duplicates
            value.startsWith("playlist:") -> PlaylistDetail(value.removePrefix("playlist:"))
            value.startsWith("add:") -> AddTracks(value.removePrefix("add:"))
            value.startsWith("tags:") -> EditTags(value.removePrefix("tags:"))
            value.startsWith("wallnew:") -> WallpaperTracks(entryId = null, newVideoUri = value.removePrefix("wallnew:"))
            value.startsWith("wall:") -> WallpaperTracks(entryId = value.removePrefix("wall:"), newVideoUri = null)
            value == "terms" -> Terms
            value == "privacy" -> Privacy
            value == "acks" -> Acknowledgements
            value.startsWith("license:") -> LicenseText(value.removePrefix("license:"))
            value.startsWith("medianew:") -> MediaTracks(entryId = null, newUri = value.removePrefix("medianew:"))
            value.startsWith("media:") -> MediaTracks(entryId = value.removePrefix("media:"), newUri = null)
            value.startsWith("show:") -> PodcastShow(value.removePrefix("show:"))
            value == "widgets" -> Widgets
            value == "stats" -> ListeningStats
            else -> null
        }
    }
}

val PageStackSaver = listSaver<List<Page>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.mapNotNull { Page.decode(it) } },
)
