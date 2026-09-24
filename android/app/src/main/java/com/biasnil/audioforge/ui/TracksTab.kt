package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.searchTracks
import com.biasnil.audioforge.data.sortTracks

@Composable
fun TracksTab(onRequestAudioPermission: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TrackSort.Title) }
    val shown = remember(tracks, query, sort) { sortTracks(searchTracks(tracks, query), sort) }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = trackCountText(shown.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            SortMenu(sort = sort, onSortChange = { sort = it })
        }

        if (tracks.isEmpty()) {
            LibraryEmptyState(onRequestAudioPermission)
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CenteredText(stringResource(R.string.search_no_results))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, track -> track.uri }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.uri == playback.current?.uri,
                        // Queue = the list as shown (searched + sorted), like the desktop table.
                        onClick = { container.playback.playQueue(shown, index) },
                        onLongClick = { onOpenPage(Page.EditTags(track.uri)) },
                    )
                }
            }
        }
    }
}

@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val clearButton: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
        {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.clear_search))
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        trailingIcon = clearButton,
        modifier = modifier,
    )
}

@Composable
private fun SortMenu(sort: TrackSort, onSortChange: (TrackSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.sort_by, stringResource(sort.label())))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun TrackSort.label(): Int = when (this) {
    TrackSort.Title -> R.string.sort_title
    TrackSort.Artist -> R.string.sort_artist
    TrackSort.Album -> R.string.sort_album
    TrackSort.Year -> R.string.sort_year
}
