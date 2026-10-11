package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.playback.QueueEntry
import com.biasnil.audioforge.playback.RepeatMode
import com.biasnil.audioforge.playback.ShuffleMode

/** How much history Up next shows, like the iOS app. */
private const val HISTORY_SHOWN = 20

/**
 * The queue, laid out like the iOS Up Next sheet: History, Now Playing,
 * the songs you queued by hand ("Playing Next", with Clear), then the rest
 * of the list playback started from ("Next From ..."). Tap a song to play
 * it; drag the handle to reorder within its section, ✕ to remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val playback = container.playback
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val snapshot by playback.queueState.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    var savingPlaylist by rememberSaveable { mutableStateOf(false) }

    val current = snapshot.current
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val played = snapshot.played.takeLast(HISTORY_SHOWN)
    val listState = rememberLazyListState(
        // Opens on the current song, with what's been played above it.
        initialFirstVisibleItemIndex = if (played.isEmpty()) 0 else played.size + 1,
    )
    val drag = remember(listState) { QueueDragState(listState) }
    LaunchedEffect(snapshot) { drag.onQueueChanged() }
    val upcoming = drag.order ?: snapshot.upcoming
    val queuedIds = drag.queuedIds ?: snapshot.upcoming.take(snapshot.queuedCount).mapTo(HashSet()) { it.id }
    // Read when a drag starts or ends (the gesture handlers outlive recompositions).
    val latestUpcoming = rememberUpdatedState(snapshot.upcoming)
    val latestQueuedIds = rememberUpdatedState(queuedIds)
    val upcomingNow = { latestUpcoming.value }
    val queuedNow = { latestQueuedIds.value }
    val queuedPart = upcoming.filter { it.id in queuedIds }
    val listPart = upcoming.filter { it.id !in queuedIds }
    val randomShuffle = state.shuffleMode == ShuffleMode.Random

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.up_next), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.done)) } },
                actions = {
                    OverflowMenu { close ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_save_playlist)) },
                            onClick = {
                                close()
                                savingPlaylist = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_clear_playing_next)) },
                            enabled = queuedPart.isNotEmpty(),
                            onClick = {
                                close()
                                playback.clearQueued()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            if (played.isNotEmpty()) {
                item(key = "historyHeader") { GroupedHeader(stringResource(R.string.queue_history)) }
                // The same song can be in the history more than once, so these are keyed by position.
                itemsIndexed(played, key = { index, _ -> "played:$index" }) { index, entry ->
                    TrackRow(
                        track = entry.item,
                        isCurrent = false,
                        onClick = { playback.jumpTo(entry.id) },
                        onLongClick = { trackActions.showMenu(entry.item) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = groupedShape(index, played.size),
                        showDivider = index < played.lastIndex,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .alpha(0.6f),
                    )
                }
            }
            item(key = "nowHeader") { GroupedHeader(stringResource(R.string.queue_now_playing)) }
            item(key = "now") {
                TrackRow(
                    track = current.item,
                    isCurrent = true,
                    onClick = {},
                    onLongClick = { trackActions.showMenu(current.item) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    showDivider = false,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (queuedPart.isNotEmpty()) {
                item(key = "queuedHeader") {
                    GroupedHeader(stringResource(R.string.queue_playing_next)) {
                        TextButton(onClick = playback::clearQueued) { Text(stringResource(R.string.queue_clear)) }
                    }
                }
                upcomingRows(queuedPart, drag, upcomingNow, queuedNow, onPlay = playback::jumpTo,
                    onRemove = playback::removeUpcoming, onMove = playback::moveUpcoming)
            }

            item(key = "listHeader") {
                GroupedHeader(
                    snapshot.contextName?.let { stringResource(R.string.queue_next_from, it) }
                        ?: stringResource(R.string.queue_next_from_list)
                )
            }
            when {
                randomShuffle -> item(key = "randomNote") {
                    NoteCard(stringResource(R.string.queue_random_note))
                }
                listPart.isEmpty() -> item(key = "emptyNote") {
                    NoteCard(
                        stringResource(if (state.repeatMode == RepeatMode.Off) R.string.queue_nothing_after else R.string.queue_repeating)
                    )
                }
                else -> upcomingRows(listPart, drag, upcomingNow, queuedNow, onPlay = playback::jumpTo,
                    onRemove = playback::removeUpcoming, onMove = playback::moveUpcoming)
            }
            if (state.shuffleMode == ShuffleMode.Smart) {
                item(key = "smartFooter") {
                    Text(
                        text = stringResource(R.string.queue_smart_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    if (savingPlaylist) {
        NewPlaylistDialog(
            onDismiss = { savingPlaylist = false },
            onCreate = { name ->
                savingPlaylist = false
                // Every song in the queue, in queue order -- not only what's left to play.
                val uris = playback.queuedTracks().map { it.uri }
                val id = container.library.createPlaylist(name)
                container.library.addToPlaylist(id, uris)
                trackActions.showMessage(
                    context.resources.getQuantityString(R.plurals.queue_saved_playlist, uris.size, uris.size, name.trim())
                )
            },
        )
    }
}

/** One section's rows (keyed by entry id, a Long -- QueueDragState relies on that). */
private fun LazyListScope.upcomingRows(
    rows: List<QueueEntry<Track>>,
    drag: QueueDragState,
    upcomingNow: () -> List<QueueEntry<Track>>,
    queuedNow: () -> Set<Long>,
    onPlay: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
) {
    itemsIndexed(rows, key = { _, entry -> entry.id }) { index, entry ->
        val dragged = entry.id == drag.draggedId
        TrackRow(
            track = entry.item,
            isCurrent = false,
            onClick = { onPlay(entry.id) },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = groupedShape(index, rows.size),
            showDivider = index < rows.lastIndex,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .then(
                    if (dragged) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = drag.offset }
                            .shadow(8.dp, RoundedCornerShape(12.dp))
                    } else {
                        Modifier.animateItem()
                    }
                ),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onRemove(entry.id) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = stringResource(R.string.remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Icon(
                        imageVector = AppIcons.DragHandle,
                        contentDescription = stringResource(R.string.queue_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .pointerInput(entry.id) {
                                detectDragGestures(
                                    onDragStart = { drag.start(entry.id, upcomingNow(), queuedNow()) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.dragBy(amount.y)
                                    },
                                    onDragEnd = { drag.finish(upcomingNow())?.let { (id, to) -> onMove(id, to) } },
                                    onDragCancel = { drag.cancel() },
                                )
                            },
                    )
                }
            },
        )
    }
}

@Composable
private fun NoteCard(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(16.dp),
    )
}

/**
 * A row being dragged by its handle. The order on screen runs ahead of the
 * queue while the finger is down and is committed when it lifts. A row
 * only moves within its own section (Playing Next, or the list).
 */
private class QueueDragState(private val listState: LazyListState) {
    var draggedId by mutableStateOf<Long?>(null)
        private set
    var order by mutableStateOf<List<QueueEntry<Track>>?>(null)
        private set
    /** Which upcoming entries are in the Playing Next section, fixed for the drag. */
    var queuedIds by mutableStateOf<Set<Long>?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    /** Layout index the dragged row should have after the last swap; until the list has been laid out again, no further swaps. */
    private var expectedIndex = -1

    fun start(id: Long, upcoming: List<QueueEntry<Track>>, queued: Set<Long>) {
        draggedId = id
        order = upcoming
        queuedIds = queued
        offset = 0f
        expectedIndex = -1
    }

    fun dragBy(dy: Float) {
        val id = draggedId ?: return
        val current = order ?: return
        val queued = queuedIds ?: return
        offset += dy
        val layout = listState.layoutInfo
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val top = dragged.offset + offset

        // Near the top or bottom edge: scroll, keeping the row under the finger.
        val edge = dragged.size / 2f
        val scroll = when {
            top < layout.viewportStartOffset + edge -> -dragged.size / 4f
            top + dragged.size > layout.viewportEndOffset - edge -> dragged.size / 4f
            else -> 0f
        }
        if (scroll != 0f) offset += listState.dispatchRawDelta(scroll)

        if (expectedIndex >= 0 && dragged.index != expectedIndex) return
        val center = dragged.offset + offset + dragged.size / 2f
        val inQueued = id in queued
        val target = layout.visibleItemsInfo.firstOrNull {
            val key = it.key
            key is Long && key != id && (key in queued) == inQueued &&
                center >= it.offset && center < it.offset + it.size
        } ?: return
        val from = current.indexOfFirst { it.id == id }
        val to = current.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        expectedIndex = dragged.index + (to - from)
        // Next layout puts the row where the target was; shift the offset so it stays under the finger.
        val newOffset = if (to > from) target.offset + target.size - dragged.size else target.offset
        offset -= newOffset - dragged.offset
    }

    /**
     * Ends the drag. Returns the entry and its new position among [upcoming]
     * (the queue as it is now -- a song may have finished meanwhile), or null
     * if it didn't move.
     */
    fun finish(upcoming: List<QueueEntry<Track>>): Pair<Long, Int>? {
        val id = draggedId
        val final = order
        draggedId = null
        offset = 0f
        if (id == null || final == null) {
            reset()
            return null
        }
        val stillQueued = upcoming.mapTo(HashSet()) { it.id }
        val from = upcoming.indexOfFirst { it.id == id }
        val to = final.filter { it.id in stillQueued }.indexOfFirst { it.id == id }
        if (from < 0 || to < 0 || from == to) {
            reset()
            return null
        }
        // [order] stays on screen until the moved queue arrives (onQueueChanged), so the row doesn't flick back for a frame.
        return id to to
    }

    fun cancel() {
        draggedId = null
        offset = 0f
        reset()
    }

    fun onQueueChanged() {
        if (draggedId == null) reset()
    }

    private fun reset() {
        order = null
        queuedIds = null
    }
}
