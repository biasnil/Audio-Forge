package com.biasnil.audioforge.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.EpisodeDownload
import com.biasnil.audioforge.data.PodcastEpisode
import com.biasnil.audioforge.data.PodcastFeed
import com.biasnil.audioforge.data.PodcastSearchResult
import com.biasnil.audioforge.data.RemoteImages
import com.biasnil.audioforge.data.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Show artwork from the web, with the app's music-note placeholder until (or unless) it loads. */
@Composable
fun RemoteArtwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(url?.let { RemoteImages.cached(it, sizePx) }, url, sizePx) {
        if (value == null && url != null) value = RemoteImages.load(url, sizePx)
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(AppIcons.Podcast, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Podcasts -> Search: shows in Apple's podcast directory; tap one to see its episodes and subscribe. */
@Composable
fun PodcastSearchPage(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val podcasts = LocalAppContainer.current.podcasts
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    fun search() {
        if (query.isBlank()) return
        keyboard?.hide()
        searching = true
        failed = false
        scope.launch {
            results = try {
                podcasts.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
            searching = false
        }
    }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.podcast_search), onBack) }) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.podcast_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus),
            )
            Text(
                text = stringResource(R.string.podcast_search_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val found = results
            when {
                searching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failed -> CenteredText(stringResource(R.string.podcast_search_failed), Modifier.fillMaxWidth().padding(32.dp))
                found != null && found.isEmpty() -> CenteredText(stringResource(R.string.podcast_search_none), Modifier.fillMaxWidth().padding(32.dp))
                found != null -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(found, key = { it.feedUrl }) { show ->
                        ShowRow(show.title, show.author, show.artworkUrl) { onOpenPage(Page.PodcastFeed(show.feedUrl, show.artworkUrl)) }
                    }
                }
            }
        }
    }
}

/** One show in a list: artwork, title, a second line, and [trailing]. */
@Composable
fun ShowRow(title: String, detail: String, artworkUrl: String?, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteArtwork(artworkUrl, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
    HorizontalDivider(Modifier.padding(start = 84.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A show from its feed: Subscribe / Unsubscribe and the episodes, newest
 * first, each with a Download button (or its progress); downloaded ones
 * play from the phone. A subscribed show shows its saved episodes at once
 * and refreshes them from the feed.
 */
@Composable
fun PodcastFeedPage(feedUrl: String, fallbackArtwork: String?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val podcasts = container.podcasts
    val trackActions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    val subscriptions by podcasts.subscriptions.collectAsStateWithLifecycle()
    val downloads by podcasts.downloads.collectAsStateWithLifecycle()
    val localEpisodes by container.library.podcasts.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val subscription = subscriptions.firstOrNull { it.feedUrl == feedUrl }

    var fetched by remember { mutableStateOf<PodcastFeed?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(feedUrl, attempt) {
        failed = false
        try {
            val feed = podcasts.fetchFeed(feedUrl)
            fetched = feed
            if (podcasts.subscription(feedUrl) != null) podcasts.updateFromFeed(feedUrl, feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(feedUrl) { podcasts.markSeen(feedUrl) }
    // Download progress, while this page is open.
    LaunchedEffect(Unit) {
        while (true) {
            podcasts.refreshDownloads()
            delay(if (podcasts.downloads.value.values.any { it is EpisodeDownload.Running }) 1_000 else 5_000)
        }
    }

    val title = fetched?.title ?: subscription?.title ?: ""
    val author = fetched?.author ?: subscription?.author ?: ""
    val artwork = fetched?.artworkUrl ?: subscription?.artworkUrl ?: fallbackArtwork
    val episodes = fetched?.episodes ?: subscription?.episodes
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val refused = stringResource(R.string.podcast_download_refused)

    Scaffold(topBar = { DetailTopBar(title, onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteArtwork(artwork, 96.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (author.isNotEmpty()) Text(author, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val feed = fetched
                        if (subscription != null) {
                            OutlinedButton(onClick = { podcasts.unsubscribe(feedUrl) }) { Text(stringResource(R.string.podcast_unsubscribe)) }
                        } else if (feed != null) {
                            Button(onClick = { podcasts.subscribe(feedUrl, feed, fallbackArtwork) }) { Text(stringResource(R.string.podcast_subscribe)) }
                        }
                    }
                }
            }
            when {
                episodes == null && failed -> item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CenteredText(stringResource(R.string.podcast_feed_failed))
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.podcast_try_again)) }
                    }
                }
                episodes == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                else -> {
                    // The downloaded episodes, in this order, so playing one carries on to the next.
                    val local = episodes.mapNotNull { podcasts.localTrack(title, it, localEpisodes)?.let { track -> it.guid to track } }.toMap()
                    items(episodes, key = { it.guid }) { episode ->
                        val track = local[episode.guid]
                        val details = listOfNotNull(
                            episode.publishedMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dateFormat) },
                            episode.durationMs.takeIf { it > 0 }?.let(::formatTime),
                        ).joinToString("  •  ")
                        EpisodeRow(
                            episode = episode,
                            details = details,
                            isNew = subscription?.newGuids?.contains(episode.guid) == true,
                            isCurrent = track != null && track.uri == playback.current?.uri,
                            download = downloads[episode.guid],
                            downloaded = track != null,
                            onPlay = {
                                if (track != null) {
                                    val queue = local.values.toList()
                                    container.playback.playQueue(queue, queue.indexOf(track), from = title)
                                }
                            },
                            onDownload = { scope.launch { if (!podcasts.download(title, episode)) trackActions.showMessage(refused) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    details: String,
    isNew: Boolean,
    isCurrent: Boolean,
    download: EpisodeDownload?,
    downloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = downloaded, onClick = onPlay)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isNew) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                downloaded -> stringResource(R.string.podcast_downloaded)
                download is EpisodeDownload.Failed -> stringResource(R.string.podcast_download_failed)
                isNew -> stringResource(R.string.podcast_new)
                else -> null
            }
            Text(
                listOfNotNull(status, details.ifEmpty { null }).joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
                color = if (download is EpisodeDownload.Failed && !downloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                downloaded -> IconButton(onClick = onPlay) {
                    Icon(AppIcons.Play, contentDescription = stringResource(R.string.play), tint = MaterialTheme.colorScheme.primary)
                }
                download is EpisodeDownload.Running -> {
                    val fraction = if (download.totalBytes > 0) download.downloadedBytes.toFloat() / download.totalBytes else null
                    val label = stringResource(R.string.podcast_downloading)
                    if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(28.dp).semantics { contentDescription = label })
                    else CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = label })
                }
                // Finished, waiting for the library to see the file.
                download is EpisodeDownload.Done -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = onDownload) {
                    Icon(AppIcons.Download, contentDescription = stringResource(R.string.podcast_download))
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
