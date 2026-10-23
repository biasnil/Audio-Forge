package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Audiobook
import com.biasnil.audioforge.data.Chapter
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.audiobooks
import com.biasnil.audioforge.data.bookProgress
import com.biasnil.audioforge.data.currentChapterIndex
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.data.nextChapterStart
import com.biasnil.audioforge.data.previousChapterStart
import kotlin.math.roundToInt

/** The books, A-Z (see Audiobooks.kt). */
@Composable
private fun rememberAudiobooks(): List<Audiobook> {
    val container = LocalAppContainer.current
    val tracks by container.library.audiobooks.collectAsStateWithLifecycle()
    return remember(tracks) { audiobooks(tracks) }
}

/** The book [uri] belongs to, if it's an audiobook. */
@Composable
private fun rememberBookOf(uri: String?): Audiobook? {
    val books = rememberAudiobooks()
    return remember(books, uri) { uri?.let { books.firstOrNull { book -> book.parts.any { it.uri == uri } } } }
}

/** [track]'s chapters, read from the file (empty while reading, or if it has none). */
@Composable
private fun rememberChapters(track: Track): List<Chapter> {
    val container = LocalAppContainer.current
    val chapters by produceState(emptyList<Chapter>(), track.uri) { value = container.chapters.chaptersFor(track.uri) }
    return chapters
}

/**
 * The Audiobooks tab: each book with how far you are and the time left.
 * Tapping one continues it where you stopped and opens Now Playing.
 */
@Composable
fun AudiobooksTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val books = rememberAudiobooks()
    val stats by container.songData.stats.collectAsStateWithLifecycle()
    val data by container.audiobookStore.data.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()

    if (books.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CenteredText(stringResource(R.string.audiobooks_empty))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(books, key = { it.key }) { book ->
            val progress = bookProgress(
                book,
                data.lastPart[book.key],
                resumeMs = { stats[it]?.resumePositionMs },
                played = { stats[it]?.played == true },
            )
            val playing = book.parts.any { it.uri == playback.current?.uri }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!playing) {
                            container.playback.playQueue(book.parts, progress.partIndex, from = book.title)
                        }
                        onOpenPage(Page.NowPlaying)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(book.parts.first(), decodeSize = 64.dp, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (playing) FontWeight.SemiBold else null,
                        color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author.isNotEmpty()) {
                        Text(
                            book.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = when {
                            progress.listenedMs <= 0 -> stringResource(R.string.audiobook_not_started, formatTime(book.durationMs))
                            progress.remainingMs <= 1_000 -> stringResource(R.string.audiobook_finished)
                            else -> stringResource(
                                R.string.audiobook_progress,
                                (progress.fraction * 100).roundToInt(),
                                formatTime(progress.remainingMs),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = 92.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Now Playing, for an audiobook: the chapter playing (tap for the
 * chapters and bookmarks page), previous / next chapter, and Add bookmark.
 * In a file without chapters, previous / next go to the book's other files.
 */
@Composable
fun BookBar(track: Track, positionMs: Long, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val playback = container.playback
    val book = rememberBookOf(track.uri) ?: return
    val chapters = rememberChapters(track)
    var addingBookmark by rememberSaveable { mutableStateOf(false) }

    val index = currentChapterIndex(chapters, positionMs)
    val part = book.parts.indexOfFirst { it.uri == track.uri }
    val label = when {
        index >= 0 -> chapters[index].title.ifEmpty { stringResource(R.string.audiobook_chapter_number, index + 1) }
        book.parts.size > 1 -> stringResource(R.string.audiobook_part, part + 1, book.parts.size)
        else -> stringResource(R.string.audiobook_chapters)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { previousChapterStart(chapters, positionMs)?.let(playback::seekTo) ?: playback.previous() }) {
            Icon(AppIcons.SkipPrevious, contentDescription = stringResource(R.string.audiobook_chapter_previous))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenPage(Page.BookChapters) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.audiobook_chapters_and_bookmarks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { nextChapterStart(chapters, positionMs)?.let(playback::seekTo) ?: playback.next() }) {
            Icon(AppIcons.SkipNext, contentDescription = stringResource(R.string.audiobook_chapter_next))
        }
        IconButton(onClick = { addingBookmark = true }) {
            Icon(AppIcons.Bookmark, contentDescription = stringResource(R.string.audiobook_add_bookmark))
        }
    }

    if (addingBookmark) {
        AddBookmarkDialog(
            positionMs = positionMs,
            onAdd = { note ->
                container.audiobookStore.addBookmark(track.uri, positionMs, note)
                addingBookmark = false
            },
            onDismiss = { addingBookmark = false },
        )
    }
}

@Composable
private fun AddBookmarkDialog(positionMs: Long, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audiobook_add_bookmark) + " · " + formatTime(positionMs)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.audiobook_bookmark_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(note) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The book playing now: its chapters (or files, for a book without chapter
 * marks) with the current one highlighted -- tap to jump -- and its
 * bookmarks, newest first: tap to go there, or delete.
 */
@Composable
fun BookChaptersPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val playback = container.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val data by container.audiobookStore.data.collectAsStateWithLifecycle()
    val track = state.current
    val book = rememberBookOf(track?.uri)
    if (track == null || book == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val chapters = rememberChapters(track)
    val position by rememberPlaybackPositionMs()
    val current = currentChapterIndex(chapters, position)
    val bookmarks = remember(data.bookmarks, book) {
        val uris = book.parts.map { it.uri }.toSet()
        data.bookmarks.filter { it.uri in uris }.sortedByDescending { it.createdMs }
    }
    var addingBookmark by rememberSaveable { mutableStateOf(false) }

    fun goTo(uri: String, positionMs: Long) {
        if (uri == track.uri) playback.seekTo(positionMs)
        else playback.playQueue(book.parts, book.parts.indexOfFirst { it.uri == uri }.coerceAtLeast(0), from = book.title, startMs = positionMs.coerceAtLeast(1))
    }

    Scaffold(topBar = { DetailTopBar(book.title, onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding)) {
            item { SectionHeader(stringResource(R.string.audiobook_chapters)) }
            if (chapters.isNotEmpty()) {
                itemsIndexed(chapters, key = { i, c -> "c$i:${c.startMs}" }) { i, chapter ->
                    ChapterRow(
                        title = chapter.title.ifEmpty { stringResource(R.string.audiobook_chapter_number, i + 1) },
                        detail = formatTime(chapter.startMs),
                        isCurrent = i == current,
                        onClick = { playback.seekTo(chapter.startMs) },
                    )
                }
            } else {
                itemsIndexed(book.parts, key = { _, part -> "p:" + part.uri }) { i, part ->
                    ChapterRow(
                        title = part.title,
                        detail = formatTime(part.durationMs),
                        isCurrent = part.uri == track.uri,
                        onClick = { if (part.uri != track.uri) playback.playQueue(book.parts, i, from = book.title) },
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, end = 16.dp)) {
                    SectionHeader(stringResource(R.string.audiobook_bookmarks), Modifier.weight(1f))
                    FilledTonalButton(onClick = { addingBookmark = true }) {
                        Icon(AppIcons.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.audiobook_add_bookmark))
                    }
                }
            }
            if (bookmarks.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.audiobook_no_bookmarks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(bookmarks, key = { "b:" + it.id }) { mark ->
                val part = book.parts.firstOrNull { it.uri == mark.uri }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { goTo(mark.uri, mark.positionMs) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        val where = if (book.parts.size > 1 && part != null) part.title + " · " else ""
                        Text(where + formatTime(mark.positionMs), style = MaterialTheme.typography.bodyLarge)
                        if (mark.note.isNotEmpty()) {
                            Text(mark.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { container.audiobookStore.deleteBookmark(mark.id) }) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.audiobook_delete_bookmark))
                    }
                }
            }
        }
    }

    if (addingBookmark) {
        AddBookmarkDialog(
            positionMs = position,
            onAdd = { note ->
                container.audiobookStore.addBookmark(track.uri, position, note)
                addingBookmark = false
            },
            onDismiss = { addingBookmark = false },
        )
    }
}

@Composable
private fun ChapterRow(title: String, detail: String, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
