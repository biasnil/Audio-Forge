package com.biasnil.audioforge.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.FreeBook
import com.biasnil.audioforge.data.FreeBookDetails
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.data.freeBookFolder
import com.biasnil.audioforge.data.freeBookThumbnailUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Audiobooks -> Find free audiobooks: LibriVox books on the Internet Archive, most popular first. */
@Composable
fun FreeBookSearchPage(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val books = LocalAppContainer.current.freeAudiobooks
    val scope = rememberCoroutineScope()
    // Coming back from a book shows the last search again.
    var query by rememberSaveable { mutableStateOf(books.lastSearch?.first.orEmpty()) }
    var results by remember { mutableStateOf(books.lastSearch?.takeIf { it.first == query }?.second) }
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
                books.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
            searching = false
        }
    }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.free_books_find), onBack) }) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.free_books_search_hint)) },
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
                text = stringResource(R.string.free_books_search_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val found = results
            when {
                searching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failed -> CenteredText(stringResource(R.string.free_books_search_failed), Modifier.fillMaxWidth().padding(32.dp))
                found != null && found.isEmpty() -> CenteredText(stringResource(R.string.free_books_search_none), Modifier.fillMaxWidth().padding(32.dp))
                found != null -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(found, key = { it.id }) { book ->
                        val detail = listOf(book.author, book.runtime).filter { it.isNotBlank() }.joinToString(" · ")
                        ShowRow(book.title, detail, freeBookThumbnailUrl(book.id), placeholder = AppIcons.Book) {
                            onOpenPage(Page.FreeBookPage(book.id))
                        }
                    }
                }
            }
        }
    }
}

/** One free book: cover, who wrote it, its parts, and Download (with progress). */
@Composable
fun FreeBookPage(id: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val books = container.freeAudiobooks
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    val details by produceState<Result<FreeBookDetails>?>(null, id, attempt) {
        value = try {
            Result.success(books.details(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val downloads by books.downloads.collectAsStateWithLifecycle()
    val audiobookTracks by container.library.audiobooks.collectAsStateWithLifecycle()
    var couldntStart by remember { mutableStateOf(false) }

    val loaded = details?.getOrNull()
    Scaffold(topBar = { DetailTopBar(loaded?.book?.title.orEmpty(), onBack) }) { innerPadding ->
        val result = details
        when {
            result == null -> Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded == null -> Column(
                Modifier.padding(innerPadding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CenteredText(stringResource(R.string.free_books_load_failed))
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.free_books_try_again)) }
            }
            else -> {
                val book = loaded.book
                val folder = "Audiobooks/${freeBookFolder(book)}/"
                val onPhone = audiobookTracks.any { it.folderKey.endsWith(folder) }
                val download = downloads[book.id]
                LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
                    item {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemoteArtwork(freeBookThumbnailUrl(book.id), 112.dp, placeholder = AppIcons.Book)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (book.author.isNotBlank()) {
                                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (book.runtime.isNotBlank()) {
                                    Text(book.runtime, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            when {
                                download != null && download.running -> {
                                    Text(
                                        stringResource(R.string.free_books_downloading, download.done, download.total),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    LinearProgressIndicator(
                                        progress = { download.done.toFloat() / download.total.coerceAtLeast(1) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    )
                                    OutlinedButton(onClick = { books.cancel(book.id) }) { Text(stringResource(R.string.free_books_stop)) }
                                }
                                download != null && download.failed > 0 -> {
                                    Text(
                                        stringResource(R.string.free_books_some_failed, download.failed, download.total),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Row {
                                        Button(onClick = { books.retry(book.id) }) { Text(stringResource(R.string.free_books_try_again)) }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = { books.cancel(book.id) }) { Text(stringResource(R.string.free_books_stop)) }
                                    }
                                }
                                else -> {
                                    if (onPhone) {
                                        Text(stringResource(R.string.free_books_on_phone), style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.size(8.dp))
                                    }
                                    val start: () -> Unit = {
                                        scope.launch { couldntStart = !books.download(loaded) }
                                    }
                                    if (onPhone) {
                                        OutlinedButton(onClick = start, enabled = loaded.parts.isNotEmpty()) {
                                            Text(stringResource(R.string.free_books_download_again))
                                        }
                                    } else {
                                        Button(onClick = start, enabled = loaded.parts.isNotEmpty()) {
                                            Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.free_books_download))
                                        }
                                    }
                                    if (couldntStart) {
                                        Text(
                                            stringResource(R.string.free_books_couldnt_start),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.free_books_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            if (loaded.description.isNotBlank()) {
                                Text(
                                    loaded.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                    item { GroupedHeader(stringResource(R.string.free_books_chapters)) }
                    itemsIndexed(loaded.parts, key = { _, part -> part.fileName }) { index, part ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp),
                            )
                            Text(part.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (part.durationMs > 0) {
                                Text(formatTime(part.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
