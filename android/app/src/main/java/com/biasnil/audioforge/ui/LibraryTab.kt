package com.biasnil.audioforge.ui

import androidx.annotation.StringRes
import com.biasnil.audioforge.R

/**
 * The desktop app's tabs, in the same order. [hideable] ones can be turned
 * off in Settings > Visible tabs (same five as the desktop); [comingInStage]
 * marks the ones still to be built.
 */
enum class LibraryTab(@StringRes val title: Int, val hideable: Boolean, val comingInStage: Int? = null) {
    Albums(R.string.tab_albums, hideable = true),
    Tracks(R.string.tab_tracks, hideable = true),
    Artists(R.string.tab_artists, hideable = true),
    Folders(R.string.tab_folders, hideable = true),
    Playlists(R.string.tab_playlists, hideable = true),
    Equalizer(R.string.tab_equalizer, hideable = false, comingInStage = 3),
    Wallpapers(R.string.tab_wallpapers, hideable = false, comingInStage = 6),
    Settings(R.string.tab_settings, hideable = false),
}
