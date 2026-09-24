package com.biasnil.audioforge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.searchTracks
import com.biasnil.audioforge.data.sortTracks

/**
 * The desktop's Wallpapers tab: a global video used for any song without
 * its own, plus videos assigned to chosen songs. The first assignment that
 * contains a song wins; turning wallpapers off and the opacity are in
 * Settings.
 */
@Composable
fun WallpapersTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val wallpapers = container.wallpapers
    val settings by container.store.settings.collectAsStateWithLifecycle()
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    val pickGlobal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) wallpapers.setGlobal(uri)
    }
    // New assignment: pick the video, then the songs (the entry is only created once songs are chosen).
    val pickForSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenPage(Page.WallpaperTracks(entryId = null, newVideoUri = uri.toString()))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader(stringResource(R.string.wallpaper_global))
        Text(
            text = stringResource(R.string.wallpaper_global_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = settings.globalWallpaperUri.ifEmpty { null }?.let(wallpapers::displayName)
                ?: stringResource(R.string.wallpaper_none),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { pickGlobal.launch(arrayOf("video/*")) }) {
                Text(stringResource(R.string.wallpaper_choose_video))
            }
            if (settings.globalWallpaperUri.isNotEmpty()) {
                TextButton(onClick = wallpapers::clearGlobal) { Text(stringResource(R.string.wallpaper_clear)) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.wallpaper_per_song))
        if (settings.wallpapers.isEmpty()) {
            Text(
                text = stringResource(R.string.wallpaper_per_song_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        settings.wallpapers.forEach { entry ->
            ListItem(
                headlineContent = {
                    Text(wallpapers.displayName(entry.videoUri), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(trackCountText(entry.trackUris.size)) },
                trailingContent = {
                    Row {
                        TextButton(onClick = { onOpenPage(Page.WallpaperTracks(entryId = entry.id, newVideoUri = null)) }) {
                            Text(stringResource(R.string.wallpaper_edit_songs))
                        }
                        TextButton(onClick = { removingId = entry.id }) { Text(stringResource(R.string.remove)) }
                    }
                },
            )
        }
        Button(
            onClick = { pickForSongs.launch(arrayOf("video/*")) },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp),
        ) {
            Icon(AppIcons.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallpaper_add))
        }
    }

    removingId?.let { id ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.wallpaper_remove_title)) },
            text = { Text(stringResource(R.string.wallpaper_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    wallpapers.removeEntry(id)
                    removingId = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Checkbox list of every song, for which songs a wallpaper video applies to
 * (the desktop's WallpaperTrackPickerDialog). Editing an assignment starts
 * with its songs ticked; a new one ([newVideoUri]) is created on Done.
 */
@Composable
fun WallpaperTracksPage(entryId: String?, newVideoUri: String?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val libraryTracks by container.library.tracks.collectAsStateWithLifecycle()
    val entry = entryId?.let { id -> settings.wallpapers.firstOrNull { it.id == id } }
    if (entry == null && newVideoUri == null) {
        LaunchedEffect(Unit) { onBack() } // assignment was removed
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(entry?.trackUris.orEmpty()) }
    val allTracks = remember(libraryTracks) { sortTracks(libraryTracks, TrackSort.Title) }
    val shown = remember(allTracks, query) { searchTracks(allTracks, query) }
    val videoName = remember(entry, newVideoUri) {
        container.wallpapers.displayName(entry?.videoUri ?: newVideoUri.orEmpty())
    }

    Scaffold(
        topBar = {
            DetailTopBar(videoName, onBack) {
                TextButton(
                    enabled = selected.isNotEmpty() || entry != null,
                    onClick = {
                        val chosen = selected.toSet()
                        val ordered = allTracks.map { it.uri }.filter { it in chosen }
                        if (entry != null) {
                            container.wallpapers.setEntryTracks(entry.id, ordered)
                        } else if (newVideoUri != null) {
                            container.wallpapers.addEntry(Uri.parse(newVideoUri), ordered)
                        }
                        onBack()
                    },
                ) { Text(stringResource(R.string.wallpaper_done_count, selected.size)) }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    val shownUris = shown.map { it.uri }
                    selected = if (shownUris.all { it in selected }) selected - shownUris.toSet() else (selected + shownUris).distinct()
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.wallpaper_select_all_shown)) }
            LazyColumn {
                items(shown, key = { it.uri }) { track ->
                    val checked = track.uri in selected
                    ListItem(
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(trackSubtitle(track), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable {
                            selected = if (checked) selected - track.uri else selected + track.uri
                        },
                    )
                }
            }
        }
    }
}
