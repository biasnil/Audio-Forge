package com.biasnil.audioforge.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.biasnil.audioforge.R

/**
 * The iOS app's tabs, in the same order. The bottom bar shows five; with
 * more visible, the fifth is "More", listing the rest. [hideable] ones can
 * be turned off in Settings > Visible tabs.
 */
enum class LibraryTab(@StringRes val title: Int, val icon: ImageVector, val hideable: Boolean = true) {
    Songs(R.string.tab_songs, AppIcons.MusicNote, hideable = false),
    Albums(R.string.tab_albums, AppIcons.Album),
    Artists(R.string.tab_artists, AppIcons.Mic),
    Genres(R.string.tab_genres, AppIcons.Genre),
    Folders(R.string.tab_folders, AppIcons.Folder),
    Playlists(R.string.tab_playlists, AppIcons.QueueMusic),
    Podcasts(R.string.tab_podcasts, AppIcons.Podcast),
    Audiobooks(R.string.tab_audiobooks, AppIcons.Book),
    Radio(R.string.tab_radio, AppIcons.Radio),
    Equalizer(R.string.tab_equalizer, AppIcons.Equalizer),
    Effects(R.string.effects, AppIcons.SurroundSound),
    Wallpapers(R.string.tab_wallpapers, AppIcons.Movie),
    Settings(R.string.tab_settings, AppIcons.Settings, hideable = false),
}

/** The hidden tabs that can be hidden (Songs and Settings always show somewhere). */
fun hiddenTabNames(hiddenTabs: Set<String>): Set<String> =
    LibraryTab.entries.filter { it.hideable && it.name in hiddenTabs }.map { it.name }.toSet()
