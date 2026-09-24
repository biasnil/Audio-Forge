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
    data class AlbumDetail(val name: String) : Page
    data class ArtistDetail(val name: String) : Page
    data class PlaylistDetail(val id: String) : Page
    data class AddTracks(val playlistId: String) : Page
    data class EditTags(val trackUri: String) : Page
    /** Songs for a wallpaper video: an existing assignment ([entryId]) or a new one ([newVideoUri]). */
    data class WallpaperTracks(val entryId: String?, val newVideoUri: String?) : Page

    fun encode(): String = when (this) {
        NowPlaying -> "now"
        is AlbumDetail -> "album:$name"
        is ArtistDetail -> "artist:$name"
        is PlaylistDetail -> "playlist:$id"
        is AddTracks -> "add:$playlistId"
        is EditTags -> "tags:$trackUri"
        is WallpaperTracks -> if (entryId != null) "wall:$entryId" else "wallnew:${newVideoUri.orEmpty()}"
    }

    companion object {
        fun decode(value: String): Page? = when {
            value == "now" -> NowPlaying
            value.startsWith("album:") -> AlbumDetail(value.removePrefix("album:"))
            value.startsWith("artist:") -> ArtistDetail(value.removePrefix("artist:"))
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
