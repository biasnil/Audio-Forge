package com.biasnil.audioforge.linkdownload

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.LinkTarget
import com.biasnil.audioforge.data.RemoteImages
import com.biasnil.audioforge.data.TitleCleaner
import com.biasnil.audioforge.data.TitleSuggestion
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.ui.AppIcons
import com.biasnil.audioforge.ui.CenteredText
import com.biasnil.audioforge.ui.DetailTopBar
import com.biasnil.audioforge.ui.InfoButton
import com.biasnil.audioforge.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Download from link: paste (or share from another app) a link to a video,
 * song or playlist; see what's behind it; download it as songs into Music
 * or as an audiobook into Audiobooks. One download at a time.
 *
 * Playlists of any size: only the rows on screen are built, their pictures
 * load as they scroll in (a few at a time), and what's picked is kept by
 * each entry's key, so scrolling or a missing picture never loses it. Songs
 * show the artist and title smart title detection suggests; any can be
 * reviewed and edited before downloading, and that's what is saved.
 */
@Composable
fun LinkDownloadPage(startTarget: LinkTarget, sharedLink: String?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val job by LinkDownloads.job.collectAsStateWithLifecycle()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val rules = settings.titleRules

    var link by rememberSaveable { mutableStateOf(sharedLink.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(startTarget) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    /** Picked entries, by [LinkEntry.key] (kept across rotation; checked against the list again). */
    var picked by rememberSaveable(stateSaver = PickedSaver) { mutableStateOf(emptyList<String>()) }
    val pickedSet = remember(picked) { picked.toHashSet() }
    /** Artist and title the user edited, by entry key; these win over any suggestion. */
    var edits by remember { mutableStateOf(emptyMap<String, SongTags>()) }
    var reviewing by remember { mutableStateOf<LinkEntry?>(null) }
    var asOneBook by rememberSaveable { mutableStateOf(true) }
    var looking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun look() {
        if (link.isBlank()) return
        keyboard?.hide()
        looking = true
        failed = false
        scope.launch {
            try {
                val found = LinkDownloads.look(context, link)
                val restored = picked.toHashSet()
                val downloadable = found.entries.filterNot { it.unavailable }.map { it.key }
                // After a rotation the same link keeps its picks; a new one starts with everything.
                picked = if (info == null && restored.isNotEmpty() && downloadable.any { it in restored }) {
                    downloadable.filter { it in restored }
                } else {
                    downloadable
                }
                if (info?.url != found.url) edits = emptyMap()
                info = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            looking = false
        }
    }

    // Shared from another app, or back after the screen was rebuilt: look it up straight away.
    LaunchedEffect(Unit) { if ((sharedLink != null || picked.isNotEmpty()) && info == null && link.isNotBlank()) look() }

    fun suggestion(entry: LinkEntry): TitleSuggestion = TitleCleaner.suggest(entry.title, entry.uploader, rules)
    fun finalTags(entry: LinkEntry): SongTags =
        edits[entry.key] ?: suggestion(entry).let { SongTags(it.artist, it.title) }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.link_title), onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item(key = "link") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        placeholder = { Text(stringResource(R.string.link_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { look() }),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { link = it; look() }
                            }) {
                                Icon(AppIcons.Description, contentDescription = stringResource(R.string.link_paste))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = target == LinkTarget.Songs,
                            onClick = { target = LinkTarget.Songs },
                            label = { Text(stringResource(R.string.link_as_songs)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = target == LinkTarget.Audiobooks,
                            onClick = { target = LinkTarget.Audiobooks },
                            label = { Text(stringResource(R.string.link_as_audiobook)) },
                        )
                        InfoButton(stringResource(if (target == LinkTarget.Songs) R.string.link_note_songs else R.string.link_note_audiobook))
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { look() }, enabled = link.isNotBlank() && !looking) { Text(stringResource(R.string.link_look)) }
                    }
                }
            }

            // The download running (or the last one's result).
            job?.let { running ->
                item(key = "job") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (!running.finished) {
                            Text(
                                stringResource(R.string.link_progress, running.done + running.failed + 1, running.total, running.current.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(
                                progress = { running.progress },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            OutlinedButton(onClick = { LinkDownloads.cancel() }) { Text(stringResource(R.string.link_stop)) }
                        } else {
                            val where = stringResource(if (running.target == LinkTarget.Songs) R.string.link_where_songs else R.string.link_where_audiobooks)
                            Text(
                                when {
                                    running.cancelled -> stringResource(R.string.link_stopped, running.done)
                                    running.failed == 0 -> stringResource(R.string.link_done, running.done, where)
                                    else -> stringResource(R.string.link_done_some_failed, running.done, running.failed, where)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (running.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(onClick = { LinkDownloads.dismiss() }) { Text(stringResource(R.string.link_ok)) }
                        }
                    }
                }
            }

            val found = info
            when {
                // Only while the list is first fetched; a list already shown stays during a new look-up.
                looking && found == null -> item(key = "looking") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        CenteredText(stringResource(R.string.link_getting_details))
                    }
                }
                failed -> item(key = "failed") { CenteredText(stringResource(R.string.link_look_failed), Modifier.fillMaxWidth().padding(32.dp)) }
                found != null && found.entries.isEmpty() -> item(key = "nothing") {
                    CenteredText(stringResource(R.string.link_nothing), Modifier.fillMaxWidth().padding(32.dp))
                }
                found != null -> {
                    val downloadable = found.entries.count { !it.unavailable }
                    val pickedCount = found.entries.count { it.key in pickedSet && !it.unavailable }
                    val several = found.entries.size > 1
                    val smart = target == LinkTarget.Songs && rules.enabled
                    item(key = "summary") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            if (looking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                            found.playlistTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            if (several) {
                                Text(
                                    pluralStringResource(R.plurals.link_item_count, found.entries.size, found.entries.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (target == LinkTarget.Audiobooks && found.playlistTitle != null) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { asOneBook = !asOneBook }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.link_one_book), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Switch(checked = asOneBook, onCheckedChange = { asOneBook = it })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val entries = found.entries.filter { it.key in pickedSet && !it.unavailable }
                                        val tags = if (smart) entries.associate { it.key to finalTags(it) } else emptyMap()
                                        LinkDownloads.start(context, container.appScope, container.library, found, entries, target, asOneBook, tags)
                                    },
                                    enabled = pickedCount > 0 && !LinkDownloads.isRunning && job?.finished != false,
                                ) {
                                    Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.link_download, pickedCount))
                                }
                                if (several) {
                                    val selectAllDescription = pluralStringResource(R.plurals.link_select_all_description, downloadable, downloadable)
                                    TextButton(
                                        onClick = { picked = found.entries.filterNot { it.unavailable }.map { it.key } },
                                        enabled = pickedCount < downloadable,
                                        modifier = Modifier.semantics { contentDescription = selectAllDescription },
                                    ) { Text(stringResource(R.string.select_all)) }
                                    TextButton(onClick = { picked = emptyList() }, enabled = pickedCount > 0) {
                                        Text(stringResource(R.string.link_clear))
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(found.entries, key = { _, entry -> entry.key }, contentType = { _, _ -> "entry" }) { index, entry ->
                        val suggestion = if (smart) remember(entry, rules) { suggestion(entry) } else null
                        LinkEntryRow(
                            entry = entry,
                            number = entry.playlistIndex ?: (index + 1),
                            showCheckbox = several,
                            checked = entry.key in pickedSet && !entry.unavailable,
                            onCheckedChange = { on ->
                                picked = if (on) picked + entry.key else picked.filterNot { it == entry.key }
                            },
                            suggestion = suggestion,
                            edited = edits[entry.key],
                            onReview = { reviewing = entry },
                        )
                    }
                }
            }
            item(key = "rights") {
                Text(
                    stringResource(R.string.link_rights, remember(info) { YtDlp.version(context) }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    reviewing?.let { entry ->
        TitleReviewDialog(
            entry = entry,
            suggestion = suggestion(entry),
            current = finalTags(entry),
            edited = entry.key in edits,
            onSave = { tags ->
                edits = edits + (entry.key to tags)
                reviewing = null
            },
            onResetToSuggestion = {
                edits = edits - entry.key
                reviewing = null
            },
            onDismiss = { reviewing = null },
        )
    }
}

/** Checkbox, number, picture, title and uploader (or the suggested artist and title). */
@Composable
private fun LinkEntryRow(
    entry: LinkEntry,
    number: Int,
    showCheckbox: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    suggestion: TitleSuggestion?,
    edited: SongTags?,
    onReview: () -> Unit,
) {
    val title = edited?.title ?: suggestion?.title ?: entry.title
    val subtitle = edited?.artist ?: suggestion?.artist ?: entry.uploader
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (showCheckbox) Modifier.toggleable(
                    value = checked,
                    enabled = !entry.unavailable,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ) else Modifier
            )
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            // The row toggles it (one announcement for TalkBack, one big target).
            Checkbox(checked = checked, onCheckedChange = null, enabled = !entry.unavailable, modifier = Modifier.padding(12.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            number.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp).padding(end = 6.dp),
        )
        LinkThumbnail(entry.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val details = listOfNotNull(
                subtitle.ifEmpty { null },
                entry.durationSeconds?.let { formatTime(it * 1000L) },
                if (entry.unavailable) stringResource(R.string.link_unavailable) else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val note = when {
                edited != null -> stringResource(R.string.link_title_edited)
                suggestion?.needsReview == true -> stringResource(R.string.link_title_check)
                suggestion?.changed == true -> stringResource(R.string.link_title_suggested)
                else -> null
            }
            if (note != null && !entry.unavailable) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (suggestion?.needsReview == true && edited == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (suggestion != null && !entry.unavailable) {
            TextButton(onClick = onReview) { Text(stringResource(R.string.link_review)) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A 16:9 picture from the playlist listing: a music note until it loads, or if there's none. */
@Composable
private fun LinkThumbnail(url: String?) {
    val widthPx = with(LocalDensity.current) { 64.dp.roundToPx() }
    // Loads when the row comes on screen; leaving the screen cancels it (produceState's coroutine).
    val bitmap by produceState(url?.let { RemoteImages.cached(it, widthPx) }, url) {
        if (value == null && url != null) value = RemoteImages.load(url, widthPx)
    }
    Box(
        Modifier
            .size(width = 64.dp, height = 36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap?.let { remember(it) { it.asImageBitmap() } }
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(AppIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        }
    }
}

/** Review details: the original name, the artist and title to save (editable), Swap, and what was removed. */
@Composable
private fun TitleReviewDialog(
    entry: LinkEntry,
    suggestion: TitleSuggestion,
    current: SongTags,
    edited: Boolean,
    onSave: (SongTags) -> Unit,
    onResetToSuggestion: () -> Unit,
    onDismiss: () -> Unit,
) {
    var artist by remember(entry.key) { mutableStateOf(current.artist) }
    var title by remember(entry.key) { mutableStateOf(current.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.link_review_original), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                if (suggestion.removed.isNotEmpty()) {
                    Text(
                        stringResource(R.string.link_review_removed, suggestion.removed.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (suggestion.needsReview) {
                    Text(
                        stringResource(R.string.link_review_check_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(R.string.link_review_artist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { val a = artist; artist = title; title = a }) {
                        Icon(AppIcons.Swap, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.link_review_swap))
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.link_review_song_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (edited) {
                    TextButton(onClick = onResetToSuggestion, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.link_review_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(SongTags(artist.trim(), title.trim())) },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picks survive rotation; a huge playlist's aren't saved (too big for saved state) and come back as "all". */
private val PickedSaver = Saver<List<String>, ArrayList<String>>(
    save = { if (it.size <= MAX_SAVED_PICKS) ArrayList(it) else null },
    restore = { it },
)

private const val MAX_SAVED_PICKS = 1500
