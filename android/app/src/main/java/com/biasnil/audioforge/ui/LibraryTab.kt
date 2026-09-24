package com.biasnil.audioforge.ui

import androidx.annotation.StringRes
import com.biasnil.audioforge.R

/** The desktop app's tabs, in the same order; [stage] is when each one gets built. */
enum class LibraryTab(@StringRes val title: Int, val stage: Int) {
    Albums(R.string.tab_albums, stage = 2),
    Tracks(R.string.tab_tracks, stage = 2),
    Artists(R.string.tab_artists, stage = 2),
    Folders(R.string.tab_folders, stage = 2),
    Playlists(R.string.tab_playlists, stage = 2),
    Equalizer(R.string.tab_equalizer, stage = 3),
    Wallpapers(R.string.tab_wallpapers, stage = 6),
    Settings(R.string.tab_settings, stage = 6),
}
