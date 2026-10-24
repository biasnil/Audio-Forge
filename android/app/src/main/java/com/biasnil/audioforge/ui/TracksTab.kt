package com.biasnil.audioforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.biasnil.audioforge.data.Track
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import com.biasnil.audioforge.data.SongAnalysis
import com.biasnil.audioforge.data.LibraryGroups
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.analysisDetail
import com.biasnil.audioforge.data.indexLetter
import com.biasnil.audioforge.data.searchTracks
import com.biasnil.audioforge.data.sortTracks
import kotlinx.coroutines.launch

/**
 * The Songs tab's state, kept by the library screen so its title bar can
 * show the sort and selection controls.
 */
class SongsTabState {
    var query by mutableStateOf("")
    var sort by mutableStateOf(TrackSort.Title)
    /** Multi-select mode (Select Songs). */
    var selecting by mutableStateOf(false)
    var selection by mutableStateOf(emptySet<String>())

    fun endSelecting() {
        selecting = false
        selection = emptySet()
    }
}

/** The songs as listed (searched + sorted), and the first row of each letter for the A-Z index. */
class ShownSongs(val songs: List<Track>, val letterTargets: List<Pair<String, Int>>)

/**
 * The Songs tab's list, worked out in the background (waiting for typing in
 * the search box to pause), so a big library never stalls the screen. The
 * search text and sort are watched from here, not read while drawing: a
 * letter typed only redraws the search box, not the whole screen. Null
 * until the first result.
 */
@Composable
fun rememberShownSongs(state: SongsTabState): ShownSongs? {
    val container = LocalAppContainer.current
    val result = remember { mutableStateOf<ShownSongs?>(null) }
    LaunchedEffect(state) {
        var lastQuery: String? = null
        combine(
            container.library.groups,
            container.analysis.analysis,
            snapshotFlow { state.query to state.sort },
        ) { groups, analysis, (query, sort) ->
            // Only the BPM / Key sorts depend on the analysis (which grows while "Analyze" runs).
            val analysisKey: Any = if (sort == TrackSort.Bpm || sort == TrackSort.Key) analysis else Unit
            SongsInputs(groups, query, sort, analysisKey)
        }
            .distinctUntilChanged()
            .collectLatest { inputs ->
                // Wait for typing to pause; a new sort or library shows at once.
                if (lastQuery != null && inputs.query != lastQuery && inputs.query.isNotBlank()) delay(SEARCH_DELAY_MS)
                lastQuery = inputs.query
                result.value = withContext(Dispatchers.Default) { shownSongs(inputs, container.analysis::analysisFor) }
            }
    }
    return result.value
}

private data class SongsInputs(val groups: LibraryGroups, val query: String, val sort: TrackSort, val analysisKey: Any)

private fun shownSongs(inputs: SongsInputs, analysisFor: (Track) -> SongAnalysis?): ShownSongs {
    val (groups, query, sort) = inputs
    // A-Z is already sorted by the library; other sorts start from it too (stable order for ties).
    val searched = searchTracks(groups.byTitle, query)
    val songs = if (sort == TrackSort.Title) searched else sortTracks(searched, sort, analysisFor)
    val letters = if (!sort.isAlphabetical) emptyList() else {
        val seen = LinkedHashMap<String, Int>()
        songs.forEachIndexed { index, track -> seen.putIfAbsent(indexLetter(track, sort), index) }
        seen.toList()
    }
    return ShownSongs(songs, letters)
}

/** How long typing must pause before the library is searched. */
const val SEARCH_DELAY_MS = 300L

/**
 * The Songs tab: search, the list (tapping a song queues exactly this list),
 * an A-Z index on alphabetical sorts, and multi-select.
 */
@Composable
fun TracksTab(state: SongsTabState, shownSongs: ShownSongs?, onRequestAudioPermission: () -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val songsLabel = stringResource(R.string.tab_songs)

    val sort = state.sort
    // Only whether there's a search matters here; the text itself is read by the search box.
    val searching by remember { derivedStateOf { state.query.isNotEmpty() } }
    val shown = shownSongs?.songs.orEmpty()
    // First row of each letter, for the index.
    val letterTargets = shownSongs?.letterTargets.orEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        SongsSearchField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (tracks.isEmpty()) {
            LibraryEmptyState(onRequestAudioPermission)
        } else if (shownSongs == null) {
            // Still sorting the library: nothing yet, rather than "no results" for a moment.
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CenteredText(stringResource(R.string.search_no_results))
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, track -> track.uri }) { index, track ->
                        if (state.selecting) {
                            val selected = track.uri in state.selection
                            TrackRow(
                                track = track,
                                isCurrent = false,
                                selected = selected,
                                onClick = {
                                    state.selection = if (selected) state.selection - track.uri else state.selection + track.uri
                                },
                            )
                        } else {
                            // BPM / key next to each song when sorted by them.
                            val detail = if (sort == TrackSort.Bpm || sort == TrackSort.Key) {
                                analysisDetail(container.analysis.analysisFor(track), sort)
                            } else null
                            TrackRow(
                                track = track,
                                isCurrent = track.uri == playback.current?.uri,
                                onClick = { container.playback.playQueue(shown, index, from = songsLabel) },
                                onLongClick = { trackActions.showMenu(track) },
                                trailing = detail?.let { text ->
                                    {
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                if (!state.selecting && !searching && letterTargets.size > 3) {
                    AlphabetIndex(
                        letters = letterTargets.map { it.first },
                        onSelect = { letter ->
                            letterTargets.firstOrNull { it.first == letter }?.let { (_, index) ->
                                scope.launch { listState.scrollToItem(index) }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp),
                    )
                }
            }
        }
    }
}

/** The Songs search box; it alone reads the text, so typing only redraws the box. */
@Composable
private fun SongsSearchField(state: SongsTabState, modifier: Modifier = Modifier) {
    SearchField(query = state.query, onQueryChange = { state.query = it }, modifier = modifier)
}

/** The iOS search bar: a filled, rounded field. */
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
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        trailingIcon = clearButton,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/** The sort button in the Songs title bar, with Select Songs under the sorts (like iOS). */
@Composable
fun SortMenu(sort: TrackSort, onSortChange: (TrackSort) -> Unit, onSelectSongs: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(AppIcons.Sort, contentDescription = stringResource(R.string.sort_by, stringResource(sort.label())))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(option.label()),
                            color = if (option == sort) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.select_songs)) },
                onClick = {
                    expanded = false
                    onSelectSongs()
                },
            )
        }
    }
}

/**
 * The Songs title bar's controls: sort / select / import normally; while
 * selecting, Done, All/None and what to do with the selection.
 */
@Composable
fun RowScope.SongsHeaderActions(state: SongsTabState, shown: List<Track>) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = container.library.importFiles(uris)
            trackActions.showMessage(context.resources.getQuantityString(R.plurals.imported_songs, copied, copied))
        }
    }

    if (!state.selecting) {
        SortMenu(sort = state.sort, onSortChange = { state.sort = it }, onSelectSongs = { state.selecting = true })
        IconButton(onClick = { importFiles.launch(arrayOf("audio/*")) }) {
            Icon(AppIcons.Add, contentDescription = stringResource(R.string.import_songs))
        }
        return
    }
    val picked = shown.filter { it.uri in state.selection }
    val all = state.selection.size == shown.size && shown.isNotEmpty()
    TextButton(onClick = { state.selection = if (all) emptySet() else shown.mapTo(HashSet()) { it.uri } }) {
        Text(stringResource(if (all) R.string.select_none else R.string.select_all))
    }
    OverflowMenu { close ->
        val act: (() -> Unit) -> Unit = { action ->
            close()
            action()
            state.endSelecting()
        }
        val enabled = picked.isNotEmpty()
        DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, enabled = enabled, onClick = {
            act { container.playback.playQueue(picked, 0, from = context.getString(R.string.selection)) }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, enabled = enabled, onClick = {
            act { trackActions.playNext(picked) }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.queue_add)) }, enabled = enabled, onClick = {
            act { trackActions.addToQueue(picked) }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_add_to_title)) }, enabled = enabled, onClick = {
            act { trackActions.addToPlaylist(picked) }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.love)) }, enabled = enabled, onClick = {
            act { container.songData.setFavorite(picked.map { it.uri }, true) }
        })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete_ellipsis), color = MaterialTheme.colorScheme.error) },
            enabled = enabled,
            onClick = { act { trackActions.delete(picked) } },
        )
    }
    TextButton(onClick = state::endSelecting) { Text(stringResource(R.string.done)) }
}

private fun TrackSort.label(): Int = when (this) {
    TrackSort.Title -> R.string.sort_title
    TrackSort.Artist -> R.string.sort_artist
    TrackSort.Album -> R.string.sort_album
    TrackSort.Year -> R.string.sort_year
    TrackSort.RecentlyAdded -> R.string.sort_recent
    TrackSort.Bpm -> R.string.sort_bpm
    TrackSort.Key -> R.string.sort_key
}
