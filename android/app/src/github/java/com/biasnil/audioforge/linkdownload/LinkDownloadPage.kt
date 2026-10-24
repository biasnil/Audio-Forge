package com.biasnil.audioforge.linkdownload

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.LinkTarget
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
 */
@Composable
fun LinkDownloadPage(startTarget: LinkTarget, sharedLink: String?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val job by LinkDownloads.job.collectAsStateWithLifecycle()

    var link by rememberSaveable { mutableStateOf(sharedLink.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(startTarget) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var picked by remember { mutableStateOf(emptySet<Int>()) }
    var asOneBook by rememberSaveable { mutableStateOf(true) }
    var looking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun look() {
        if (link.isBlank()) return
        keyboard?.hide()
        looking = true
        failed = false
        info = null
        scope.launch {
            try {
                val found = LinkDownloads.look(context, link)
                info = found
                picked = found.entries.indices.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            looking = false
        }
    }

    // Shared from another app: look it up straight away.
    LaunchedEffect(Unit) { if (sharedLink != null && info == null) look() }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.link_title), onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item {
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
                item {
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
                looking -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                failed -> item { CenteredText(stringResource(R.string.link_look_failed), Modifier.fillMaxWidth().padding(32.dp)) }
                found != null && found.entries.isEmpty() -> item { CenteredText(stringResource(R.string.link_nothing), Modifier.fillMaxWidth().padding(32.dp)) }
                found != null -> {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            found.playlistTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
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
                                        val entries = found.entries.filterIndexed { index, _ -> index in picked }
                                        LinkDownloads.start(context, container.appScope, container.library, found, entries, target, asOneBook)
                                    },
                                    enabled = picked.isNotEmpty() && !LinkDownloads.isRunning && job?.finished != false,
                                ) {
                                    Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.link_download, picked.size))
                                }
                                if (found.entries.size > 1) {
                                    val all = picked.size == found.entries.size
                                    TextButton(onClick = { picked = if (all) emptySet() else found.entries.indices.toSet() }) {
                                        Text(stringResource(if (all) R.string.select_none else R.string.select_all))
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(found.entries) { index, entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = found.entries.size > 1) { picked = if (index in picked) picked - index else picked + index }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (found.entries.size > 1) {
                                Checkbox(checked = index in picked, onCheckedChange = { picked = if (it) picked + index else picked - index })
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (entry.uploader.isNotEmpty()) {
                                    Text(entry.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.link_rights, remember(info) { YtDlp.version(context) }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
