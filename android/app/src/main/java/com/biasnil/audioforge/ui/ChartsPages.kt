package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.CHART_COUNTRIES
import com.biasnil.audioforge.data.CachedChart
import com.biasnil.audioforge.data.ChartEntry
import com.biasnil.audioforge.data.ChartKind
import com.biasnil.audioforge.data.ChartLoad
import com.biasnil.audioforge.data.defaultChartCountry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A country's name in the app's language ("United Kingdom", "Royaume-Uni"). */
private fun countryName(code: String): String =
    Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault()).ifEmpty { code.uppercase(Locale.ROOT) }

/**
 * The Charts tab: Apple Music's Top songs or Top albums for a country,
 * loaded when the tab is opened and at most once a day. Tap a song for
 * Open in Apple Music or Find on YouTube -- both open the other app; nothing
 * is downloaded. The bell in the title follows the chart: new entries get a
 * New label the next day it's opened.
 */
@Composable
fun ChartsTab() {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val store = container.store
    val repo = container.charts
    val settings by store.settings.collectAsStateWithLifecycle()
    val charts by repo.charts.collectAsStateWithLifecycle()
    val follows by repo.follows.collectAsStateWithLifecycle()
    val loadingKeys by repo.loading.collectAsStateWithLifecycle()
    val kind = settings.chartKind
    val country = settings.chartCountry.ifEmpty { defaultChartCountry() }
    val key = repo.key(kind, country)
    val chart = charts[key]
    val follow = follows[key]
    /** The last country whose chart loaded: Change country goes back to it if the new one has none. */
    var lastGoodCountry by rememberSaveable { mutableStateOf(settings.chartCountry) }
    var status by remember(key) { mutableStateOf<ChartLoad?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ChartEntry?>(null) }
    val unsupportedMessage = stringResource(R.string.chart_unsupported_country)

    LaunchedEffect(key, retry) {
        val result = repo.open(kind, country)
        status = result
        when (result) {
            ChartLoad.Unsupported -> if (repo.chart(kind, country) == null && settings.chartCountry != lastGoodCountry) {
                trackActions.showMessage(unsupportedMessage)
                store.update { it.copy(chartCountry = lastGoodCountry) }
            }
            ChartLoad.Fresh, ChartLoad.Updated -> lastGoodCountry = settings.chartCountry
            ChartLoad.Failed -> Unit
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "choose") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ChartKind.Songs to R.string.chart_top_songs, ChartKind.Albums to R.string.chart_top_albums).forEach { (option, label) ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { store.update { it.copy(chartKind = option) } },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                CountryRow(
                    title = stringResource(if (kind == ChartKind.Albums) R.string.chart_top_albums else R.string.chart_top_songs),
                    country = country,
                    onChange = { picking = true },
                )
                if (follow != null) {
                    Text(
                        stringResource(R.string.chart_following_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        chartBody(
            chart = chart,
            loading = key in loadingKeys,
            failed = status == ChartLoad.Failed,
            unsupported = status == ChartLoad.Unsupported,
            newIds = follow?.newIds.orEmpty(),
            placeholder = if (kind == ChartKind.Albums) AppIcons.Album else AppIcons.MusicNote,
            onRetry = { retry++ },
            onClick = { actionsFor = it },
        )
        item(key = "source") { ChartSourceNote() }
    }

    if (picking) {
        CountryPickerDialog(
            current = settings.chartCountry,
            onPick = { picked ->
                picking = false
                store.update { it.copy(chartCountry = picked) }
            },
            onDismiss = { picking = false },
        )
    }
    actionsFor?.let { entry ->
        ChartEntryActions(
            entry = entry,
            onOpenApple = {
                actionsFor = null
                entry.url?.let { openLink(context, it) }
            },
            onFindOnYouTube = {
                actionsFor = null
                openYouTubeSearch(context, entry)
            },
            onDismiss = { actionsFor = null },
        )
    }
}

/** The Charts title-bar bell: Follow this chart / Stop following. */
@Composable
fun ChartsHeaderActions() {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val follows by container.charts.follows.collectAsStateWithLifecycle()
    val kind = settings.chartKind
    val country = settings.chartCountry.ifEmpty { defaultChartCountry() }
    val followed = container.charts.key(kind, country) in follows
    IconButton(onClick = {
        if (followed) {
            container.charts.unfollow(kind, country)
            trackActions.showMessage(context.getString(R.string.chart_unfollowed))
        } else {
            container.charts.follow(kind, country)
            trackActions.showMessage(context.getString(R.string.chart_followed))
        }
    }) {
        Icon(
            if (followed) AppIcons.Bell else AppIcons.BellOutline,
            contentDescription = stringResource(if (followed) R.string.chart_unfollow else R.string.chart_follow),
            tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Podcasts -> Top podcasts: Apple's chart for a country, from the cache
 * when it's from today (Apple is asked at most once a day, and only when
 * this page opens). Subscribe straight from the list, or tap a show to see
 * its episodes.
 */
@Composable
fun PodcastChartPage(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = container.store
    val repo = container.charts
    val podcasts = container.podcasts
    val settings by store.settings.collectAsStateWithLifecycle()
    val charts by repo.charts.collectAsStateWithLifecycle()
    val loadingKeys by repo.loading.collectAsStateWithLifecycle()
    val subscriptions by podcasts.subscriptions.collectAsStateWithLifecycle()
    val country = settings.podcastChartCountry.ifEmpty { defaultChartCountry() }
    val key = repo.key(ChartKind.Podcasts, country)
    val chart = charts[key]
    var lastGoodCountry by rememberSaveable { mutableStateOf(settings.podcastChartCountry) }
    var status by remember(key) { mutableStateOf<ChartLoad?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    /** Apple ids being opened or subscribed to (their feed is looked up first). */
    var busy by remember { mutableStateOf(emptySet<String>()) }
    /** Apple id -> feed, for shows subscribed from here (so they say Subscribed). */
    var feeds by remember { mutableStateOf(emptyMap<String, String>()) }
    val unsupportedMessage = stringResource(R.string.chart_unsupported_country)

    LaunchedEffect(key, retry) {
        val result = repo.open(ChartKind.Podcasts, country)
        status = result
        when (result) {
            ChartLoad.Unsupported -> if (repo.chart(ChartKind.Podcasts, country) == null && settings.podcastChartCountry != lastGoodCountry) {
                trackActions.showMessage(unsupportedMessage)
                store.update { it.copy(podcastChartCountry = lastGoodCountry) }
            }
            ChartLoad.Fresh, ChartLoad.Updated -> lastGoodCountry = settings.podcastChartCountry
            ChartLoad.Failed -> Unit
        }
    }

    fun withFeed(entry: ChartEntry, then: suspend (String) -> Unit) {
        if (entry.id in busy) return
        busy = busy + entry.id
        scope.launch {
            try {
                val feed = repo.podcastFeedUrl(entry.id)
                if (feed == null) {
                    trackActions.showMessage(context.getString(R.string.chart_podcast_no_feed))
                } else {
                    feeds = feeds + (entry.id to feed)
                    then(feed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trackActions.showMessage(context.getString(R.string.chart_podcast_failed))
            } finally {
                busy = busy - entry.id
            }
        }
    }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.podcast_top), onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item(key = "country") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    CountryRow(stringResource(R.string.podcast_top), country, onChange = { picking = true })
                }
            }
            chartBody(
                chart = chart,
                loading = key in loadingKeys,
                failed = status == ChartLoad.Failed,
            unsupported = status == ChartLoad.Unsupported,
                newIds = emptySet(),
                placeholder = AppIcons.Podcast,
                onRetry = { retry++ },
                onClick = { entry -> withFeed(entry) { feed -> onOpenPage(Page.PodcastFeed(feed, entry.artworkUrl)) } },
                trailing = { entry ->
                    val feed = feeds[entry.id]
                    val subscribed = subscriptions.any { it.feedUrl == feed || it.title.equals(entry.title, ignoreCase = true) }
                    when {
                        entry.id in busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        subscribed -> Text(
                            stringResource(R.string.chart_subscribed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        else -> {
                            val description = stringResource(R.string.chart_subscribe_to, entry.title)
                            FilledTonalButton(
                                onClick = {
                                    withFeed(entry) { feedUrl ->
                                        val fetched = podcasts.fetchFeed(feedUrl)
                                        podcasts.subscribe(feedUrl, fetched, entry.artworkUrl)
                                        trackActions.showMessage(context.getString(R.string.chart_subscribed_to, entry.title))
                                    }
                                },
                                modifier = Modifier.semantics { contentDescription = description },
                            ) { Text(stringResource(R.string.podcast_subscribe)) }
                        }
                    }
                },
            )
            item(key = "source") { ChartSourceNote() }
        }
    }

    if (picking) {
        CountryPickerDialog(
            current = settings.podcastChartCountry,
            onPick = { picked ->
                picking = false
                store.update { it.copy(podcastChartCountry = picked) }
            },
            onDismiss = { picking = false },
        )
    }
}

/** "Top songs — United Kingdom" and Change country. */
@Composable
private fun CountryRow(title: String, country: String, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.chart_title_country, title, countryName(country)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text(stringResource(R.string.chart_change_country)) }
    }
}

/** The ranked list, or loading / offline: cached entries always stay visible while a refresh runs. */
private fun LazyListScope.chartBody(
    chart: CachedChart?,
    loading: Boolean,
    failed: Boolean,
    unsupported: Boolean = false,
    newIds: Set<String>,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    onRetry: () -> Unit,
    onClick: (ChartEntry) -> Unit,
    trailing: (@Composable (ChartEntry) -> Unit)? = null,
) {
    if (chart == null) {
        item(key = "state") {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (unsupported && !loading) {
                    CenteredText(stringResource(R.string.chart_unsupported_country))
                } else if (failed && !loading) {
                    CenteredText(stringResource(R.string.chart_offline))
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = onRetry) { Text(stringResource(R.string.podcast_try_again)) }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }
    item(key = "updated") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                updatedText(chart.fetchedMs) + if (failed) " · " + stringResource(R.string.chart_refresh_failed) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (failed && !loading) TextButton(onClick = onRetry) { Text(stringResource(R.string.podcast_try_again)) }
        }
    }
    itemsIndexed(chart.entries, key = { _, entry -> "e:" + entry.id }) { index, entry ->
        ChartRow(index + 1, entry, isNew = entry.id in newIds, placeholder = placeholder, onClick = { onClick(entry) }, trailing = trailing)
    }
}

@Composable
private fun ChartRow(
    rank: Int,
    entry: ChartEntry,
    isNew: Boolean,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    trailing: (@Composable (ChartEntry) -> Unit)?,
) {
    val newLabel = stringResource(R.string.chart_new)
    val rankLabel = stringResource(R.string.chart_rank, rank)
    val explicitLabel = stringResource(R.string.chart_explicit)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 32.dp)
                .semantics { contentDescription = rankLabel },
        )
        RemoteArtwork(entry.artworkUrl, 56.dp, placeholder = placeholder)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.explicit) {
                    Text(
                        "E",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                            .padding(horizontal = 3.dp)
                            .semantics { contentDescription = explicitLabel },
                    )
                }
                Text(
                    entry.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isNew) {
                Text(newLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        trailing?.invoke(entry)
    }
    HorizontalDivider(Modifier.padding(start = 108.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Updated today / Updated yesterday / Updated 26 Oct 2026. */
@Composable
private fun updatedText(fetchedMs: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(fetchedMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.chart_updated_today)
        today.minusDays(1) -> stringResource(R.string.chart_updated_yesterday)
        else -> stringResource(R.string.chart_updated_on, day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
}

@Composable
private fun ChartSourceNote() {
    Text(
        stringResource(R.string.chart_source_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Change country: a searchable list in the app's language, with the phone's own country first. */
@Composable
private fun CountryPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val countries = remember { CHART_COUNTRIES.map { it to countryName(it) }.sortedBy { it.second } }
    val shown = remember(query) {
        val q = normalizeForSearch(query)
        if (q.isEmpty()) countries else countries.filter { (code, name) -> normalizeForSearch(name).contains(q) || code == q }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chart_change_country)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.chart_country_search)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (query.isBlank()) {
                        item(key = "phone") {
                            CountryOption(
                                stringResource(R.string.chart_country_phone, countryName(defaultChartCountry())),
                                selected = current.isEmpty(),
                            ) { onPick("") }
                        }
                    }
                    items(shown, key = { it.first }) { (code, name) ->
                        CountryOption(name, selected = current == code) { onPick(code) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CountryOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** A chart song's two choices; both open another app, and the chart stays where it was. */
@Composable
private fun ChartEntryActions(entry: ChartEntry, onOpenApple: () -> Unit, onFindOnYouTube: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(entry.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(16.dp))
                if (entry.url != null) {
                    OutlinedButton(onClick = onOpenApple, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chart_open_apple))
                    }
                }
                OutlinedButton(onClick = onFindOnYouTube, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.chart_find_youtube))
                }
                Text(
                    stringResource(R.string.chart_find_youtube_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Opens YouTube's own search for the song (the YouTube app if it's
 * installed, else the browser), so the user picks the right video there.
 * Only artist and title are sent; nothing is downloaded or played here.
 */
private fun openYouTubeSearch(context: android.content.Context, entry: ChartEntry) {
    val query = URLEncoder.encode("${entry.artist} ${entry.title}".trim(), "UTF-8")
    openLink(context, "https://www.youtube.com/results?search_query=$query")
}
