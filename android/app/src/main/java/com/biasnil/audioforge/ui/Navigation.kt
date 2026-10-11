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
            else -> null
        }
    }
}

val PageStackSaver = listSaver<List<Page>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.mapNotNull { Page.decode(it) } },
)
