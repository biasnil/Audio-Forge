package com.biasnil.audioforge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.RADIO_FIRST_PAGE
import com.biasnil.audioforge.data.RADIO_NEXT_PAGE
import com.biasnil.audioforge.data.RadioCountry
import com.biasnil.audioforge.data.RadioStation
import com.biasnil.audioforge.data.countryName
import com.biasnil.audioforge.data.stationDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The Radio tab: internet radio from Radio Browser (see RadioRepository).
 * Search by station name or tag, filter by country; with no search, your
 * Favorites, Recently played and the Top stations. Tap a station to play it
 * (it replaces the queue), the heart to keep it, long-press for more.
 * Nothing is fetched until this tab is opened, nothing plays until a tap.
 */
@Composable
fun RadioTab() {
    val container = LocalAppContainer.current
    val radio = container.radio
    val locale = LocalConfiguration.current.locales[0]
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val favorites by radio.favorites.collectAsStateWithLifecycle()
    val recent by radio.recent.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var byTag by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf("") }
    var choosingCountry by remember { mutableStateOf(false) }
    // What's searched (set when the search is sent, so typing doesn't fetch).
    var searched by rememberSaveable { mutableStateOf("") }
    val browsing = searched.isNotBlank() || country.isNotEmpty()

    // A search's results, page by page (500, then 1000 at a time, as the desktop).
    var results by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var top by remember { mutableStateOf<List<RadioStation>?>(null) }

    suspend fun loadPage(first: Boolean) {
        loading = true
        failed = false
        try {
            val name = if (byTag) "" else searched
            val tag = if (byTag) searched else ""
            val size = if (first) RADIO_FIRST_PAGE else RADIO_NEXT_PAGE
            val page = radio.search(name, country, tag, offset = if (first) 0 else results.size, limit = size)
            results = if (first) page else (results + page).distinctBy { it.uuid }
            hasMore = page.size == size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
        loading = false
    }

    LaunchedEffect(searched, byTag, country, attempt) {
        if (browsing) {
            results = emptyList()
            loadPage(first = true)
        } else if (top == null) {
            failed = false
            top = try {
                radio.topStations()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
        }
    }

    val listState = rememberLazyListState()
    // Near the end of the results: the next page.
    LaunchedEffect(listState, browsing) {
        snapshotFlow { listState.nearEnd() }.collect { nearEnd ->
            if (nearEnd && browsing && hasMore && !loading && !failed) loadPage(first = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) searched = ""
            },
            placeholder = { Text(stringResource(if (byTag) R.string.radio_search_tag_hint else R.string.radio_search_hint)) },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                searched = query.trim()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = country.isNotEmpty(),
                onClick = { choosingCountry = true },
                label = { Text(if (country.isEmpty()) stringResource(R.string.radio_all_countries) else countryName(country, locale)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = byTag,
                onClick = { byTag = !byTag },
                label = { Text(stringResource(R.string.radio_search_tags)) },
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (browsing) {
                items(results, key = { "r:" + it.uuid }) { station -> StationRow(station, locale) }
                when {
                    loading -> item { Loading() }
                    failed -> item { Failed { attempt++ } }
                    results.isEmpty() -> item { CenteredText(stringResource(R.string.radio_none), Modifier.fillMaxWidth().padding(32.dp)) }
                }
            } else {
                if (favorites.isNotEmpty()) {
                    item { GroupedHeader(stringResource(R.string.radio_favorites)) }
                    items(favorites, key = { "f:" + it.uuid }) { station -> StationRow(station, locale) }
                }
                if (recent.isNotEmpty()) {
                    item { GroupedHeader(stringResource(R.string.radio_recent)) }
                    items(recent, key = { "h:" + it.uuid }) { station -> StationRow(station, locale) }
                }
                item { GroupedHeader(stringResource(R.string.radio_top)) }
                val topStations = top
                when {
                    topStations != null -> items(topStations, key = { "t:" + it.uuid }) { station -> StationRow(station, locale) }
                    failed -> item { Failed { attempt++ } }
                    else -> item { Loading() }
                }
                item {
                    Text(
                        text = stringResource(R.string.radio_directory_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (choosingCountry) {
        CountryDialog(
            selected = country,
            locale = locale,
            onPick = {
                country = it
                choosingCountry = false
                if (it.isNotEmpty()) scope.launch { listState.scrollToItem(0) }
            },
            onDismiss = { choosingCountry = false },
        )
    }
}

private fun LazyListState.nearEnd(): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return last >= layoutInfo.totalItemsCount - 20
}

/** One station: logo, name, details, the heart; tap plays, long-press for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(station: RadioStation, locale: Locale) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val trackActions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val favoriteIds by container.radio.favoriteIds.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val playing = playback.current?.station?.uuid == station.uuid
    val favorite = station.uuid in favoriteIds
    var menu by remember { mutableStateOf(false) }
    val details = remember(station, locale) {
        stationDetails(station, station.countryCode.takeIf { it.isNotEmpty() }?.let { countryName(it, locale) })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { container.playStation(station) }, onLongClick = { menu = true })
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteArtwork(station.favicon, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { container.radio.toggleFavorite(station) }) {
            Icon(
                if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                contentDescription = stringResource(if (favorite) R.string.radio_unfavorite else R.string.radio_favorite),
                tint = if (favorite) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

    if (menu) {
        val voted = stringResource(R.string.radio_voted)
        val voteFailed = stringResource(R.string.radio_vote_failed)
        MenuDialog(title = station.name, onDismiss = { menu = false }) { close ->
            MenuDialogButton(stringResource(if (favorite) R.string.radio_unfavorite else R.string.radio_favorite)) {
                close()
                container.radio.toggleFavorite(station)
            }
            MenuDialogButton(stringResource(R.string.radio_vote)) {
                close()
                scope.launch { trackActions.showMessage(if (container.radio.vote(station)) voted else voteFailed) }
            }
            if (station.homepage.startsWith("http")) {
                MenuDialogButton(stringResource(R.string.radio_homepage)) {
                    close()
                    openLink(context, station.homepage)
                }
            }
        }
    }
}

/** Countries with stations, by name in the app's language; "All countries" first. */
@Composable
private fun CountryDialog(selected: String, locale: Locale, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val radio = LocalAppContainer.current.radio
    var countries by remember { mutableStateOf<List<Pair<RadioCountry, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            countries = radio.countries().map { it to countryName(it.code, locale) }.sortedBy { it.second.lowercase(locale) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.radio_country)) },
        text = {
            val list = countries
            when {
                list != null -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { CountryRow(stringResource(R.string.radio_all_countries), null, selected.isEmpty()) { onPick("") } }
                    items(list, key = { it.first.code }) { (country, name) ->
                        CountryRow(name, country.stations, country.code == selected) { onPick(country.code) }
                    }
                }
                failed -> Text(stringResource(R.string.radio_failed))
                else -> Loading()
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CountryRow(name: String, stations: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (stations != null) {
            Text("$stations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp))
    }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CenteredText(stringResource(R.string.radio_failed))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.podcast_try_again)) }
    }
}
