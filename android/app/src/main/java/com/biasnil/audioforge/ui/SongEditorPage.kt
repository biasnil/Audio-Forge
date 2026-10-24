package com.biasnil.audioforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.edit.AudioEdit
import com.biasnil.audioforge.edit.EditPreview
import com.biasnil.audioforge.edit.EditSaver
import com.biasnil.audioforge.edit.PcmSource
import com.biasnil.audioforge.edit.normalizeGainDb
import com.biasnil.audioforge.lyrics.EditLine
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.editLinesFrom
import com.biasnil.audioforge.lyrics.formatLrc
import com.biasnil.audioforge.lyrics.shiftedBy
import com.biasnil.audioforge.lyrics.withTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Edit this song: two tabs. Audio -- select part of the waveform, then cut
 * it out (the rest closes up) or keep only it (trims the start and end);
 * change the volume or normalize; hear it; save as a copy or over the
 * original. Lyric -- the synced lyrics against a close-up of the waveform:
 * set when each line starts, nudge it, shift them all; save them as the
 * song's lyrics, or export a .lrc file.
 */
@Composable
fun SongEditorPage(trackUri: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track = remember(trackUri) { container.library.trackFor(trackUri) ?: container.playback.state.value.current?.takeIf { it.uri == trackUri } }
    if (track == null) {
        Scaffold(topBar = { DetailTopBar(stringResource(R.string.editor_title), onBack) }) { padding ->
            CenteredText(stringResource(R.string.editor_missing), Modifier.padding(padding).padding(32.dp))
        }
        return
    }

    var source by remember { mutableStateOf<PcmSource?>(null) }
    var decodeProgress by remember { mutableStateOf(0f) }
    var decodeFailed by remember { mutableStateOf(false) }
    val workDir = remember { File(context.cacheDir, "song-editor") }

    // The song playing in the app would talk over the preview; a keyboard left open (from a search) covers the editor.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        container.playback.pause()
        keyboard?.hide()
    }
    LaunchedEffect(trackUri) {
        try {
            source = withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                PcmSource.decode(context, Uri.parse(trackUri), File(workDir, "source.pcm")) { decodeProgress = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            decodeFailed = true
        }
    }
    DisposableEffect(Unit) { onDispose { workDir.deleteRecursively() } }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { DetailTopBar(track.title, onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.editor_audio)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.editor_lyric)) })
            }
            val loaded = source
            when {
                decodeFailed -> CenteredText(stringResource(R.string.editor_cant_open), Modifier.fillMaxWidth().padding(32.dp))
                loaded == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.editor_opening))
                    LinearProgressIndicator(progress = { decodeProgress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                // Each tab has its own preview, stopped when the tab is left.
                tab == 0 -> AudioTab(track, loaded, workDir)
                else -> LyricTab(track, loaded)
            }
        }
    }
}

// --- Lyric ------------------------------------------------------------------

@Composable
private fun LyricTab(track: Track, source: PcmSource) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whole = remember(source) { AudioEdit.whole(source.frames) }
    val preview = remember(source) { EditPreview(source, scope) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val playing by preview.playing.collectAsStateWithLifecycle()
    val position by preview.position.collectAsStateWithLifecycle()
    val positionMs = source.framesToMs(position)

    var lines by remember { mutableStateOf<List<EditLine>?>(null) }
    var changed by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track.uri) {
        val state = container.lyrics.lyricsFor(track)
        lines = if (state is LyricsState.Synced || state is LyricsState.Plain) {
            editLinesFrom(state, container.songData.statsFor(track.uri).lyricsOffsetMs)
        } else emptyList()
    }
    fun update(next: List<EditLine>) {
        lines = next
        changed = true
    }

    // Export: Android's "save as" screen, starting in the song's folder, with the song's name.
    val export = rememberLauncherForActivityResult(CreateLrcDocument(initialFolderOf(track))) { uri ->
        val current = lines ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(formatLrc(current, track.title, track.artist).toByteArray()) }
                    // So a later rename or retitle of the song can update it too.
                    container.sidecarLyrics.rememberExport(track.uri, uri)
                }.isSuccess
            }
            message = context.getString(if (ok) R.string.editor_lrc_exported else R.string.editor_save_failed)
        }
    }

    val all = lines
    if (all == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (all.isEmpty()) {
        CenteredText(stringResource(R.string.editor_no_lyrics), Modifier.fillMaxWidth().padding(32.dp))
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // A close-up: 10 seconds around the playhead, with a mark where each line starts.
            val window = source.msToFrames(WINDOW_MS)
            val from = (position - window / 2).coerceIn(0, (source.frames - window).coerceAtLeast(0))
            Waveform(
                source = source,
                edit = whole,
                from = from,
                to = (from + window).coerceAtMost(source.frames),
                playhead = position,
                selection = null,
                markers = all.mapNotNull { it.timeMs?.let(source::msToFrames) },
                onTap = { frame -> preview.seek(frame) },
                onSelect = null,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (playing) preview.stop() else preview.play(whole, position) }) {
                    Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = stringResource(if (playing) R.string.editor_stop else R.string.editor_play))
                }
                IconButton(onClick = { preview.seek(position - source.msToFrames(SKIP_MS)) }) {
                    Icon(AppIcons.Replay, contentDescription = stringResource(R.string.editor_back_5))
                }
                Text(formatPrecise(positionMs), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.editor_shift_all), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { update(all.shiftedBy(-NUDGE_MS)) }) { Text("−0.05") }
                TextButton(onClick = { update(all.shiftedBy(NUDGE_MS)) }) { Text("+0.05") }
                InfoButton(stringResource(R.string.editor_lyric_explanation), title = stringResource(R.string.editor_lyric))
            }
        }

        val listState = rememberLazyListState()
        val current = currentEditLine(all, positionMs)
        LaunchedEffect(current, playing) { if (playing && current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
        LazyColumn(Modifier.weight(1f), state = listState) {
            itemsIndexed(all) { index, line ->
                val isSelected = index == selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable {
                            selected = index
                            line.timeMs?.let { preview.seek(source.msToFrames(it)) }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line.timeMs?.let(::formatPrecise) ?: "--:--.--",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            line.text.ifEmpty { "♪" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == current) FontWeight.SemiBold else null,
                            color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isSelected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = {
                                update(all.withTime(index, positionMs))
                                // On to the next line, ready for its moment.
                                selected = (index + 1).coerceAtMost(all.lastIndex)
                            }) { Text(stringResource(R.string.editor_set_now)) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { line.timeMs?.let { update(all.withTime(index, it - NUDGE_MS)) } }, enabled = line.timeMs != null) { Text("−0.05") }
                            TextButton(onClick = { line.timeMs?.let { update(all.withTime(index, it + NUDGE_MS)) } }, enabled = line.timeMs != null) { Text("+0.05") }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        container.lyrics.saveSynced(track, formatLrc(all))
                        // The new times already include any sync offset.
                        container.songData.setLyricsOffset(track.uri, 0)
                        changed = false
                        message = context.getString(R.string.editor_lyrics_saved)
                    }
                },
                enabled = changed,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.editor_lyrics_save)) }
            OutlinedButton(
                onClick = { export.launch(track.fileName.substringBeforeLast('.').ifEmpty { track.title } + ".lrc") },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.editor_lrc_export)) }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.info_got_it)) } },
        )
    }
}

/** The line being sung at [positionMs]: the last timed line that's started. */
private fun currentEditLine(lines: List<EditLine>, positionMs: Long): Int {
    var index = -1
    lines.forEachIndexed { i, line -> if (line.timeMs != null && line.timeMs <= positionMs) index = i }
    return index
}

// --- Waveform ---------------------------------------------------------------

/**
 * The edit's waveform between [from] and [to] (edited timeline), with the
 * playhead, a selection and [markers]. Tap to move the playhead; drag
 * sideways to select (when [onSelect] is given).
 */
@Composable
private fun Waveform(
    source: PcmSource,
    edit: AudioEdit,
    from: Long,
    to: Long,
    playhead: Long,
    selection: LongRange?,
    onTap: (Long) -> Unit,
    onSelect: ((LongRange) -> Unit)?,
    modifier: Modifier = Modifier,
    markers: List<Long> = emptyList(),
) {
    val wave = MaterialTheme.colorScheme.primary
    val selected = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val head = MaterialTheme.colorScheme.onSurface
    val mark = MaterialTheme.colorScheme.tertiary
    val span = (to - from).coerceAtLeast(1)
    fun frameAt(x: Float, width: Int) = from + (x / width.coerceAtLeast(1) * span).toLong().coerceIn(0, span)

    var width by remember { mutableIntStateOf(0) }
    val bars = remember(source, edit, from, to, width) { if (width == 0) FloatArray(0) else waveBars(source, edit, from, to, width / BAR_PX) }
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged { width = it.width }
            .pointerInput(from, to) { detectTapGestures { onTap(frameAt(it.x, size.width)) } }
            .let { m ->
                if (onSelect == null) m else m.pointerInput(from, to) {
                    var anchor = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { anchor = frameAt(it.x, size.width) },
                        onHorizontalDrag = { change, _ ->
                            val now = frameAt(change.position.x, size.width)
                            onSelect(minOf(anchor, now)..maxOf(anchor, now))
                        },
                    )
                }
            },
    ) {
        val mid = size.height / 2
        val barWidth = size.width / bars.size.coerceAtLeast(1)
        selection?.let { range ->
            val left = (range.first - from).toFloat() / span * size.width
            val right = (range.last - from).toFloat() / span * size.width
            drawRect(selected, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        }
        bars.forEachIndexed { i, level ->
            val h = (level * mid).coerceAtLeast(1f)
            drawRect(wave, topLeft = Offset(i * barWidth, mid - h), size = Size((barWidth - 1f).coerceAtLeast(1f), h * 2))
        }
        for (marker in markers) {
            if (marker < from || marker > to) continue
            val x = (marker - from).toFloat() / span * size.width
            drawLine(mark, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
        if (playhead in from..to) {
            val x = (playhead - from).toFloat() / span * size.width
            drawLine(head, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

/** [count] levels (0..1): the loudest moment of each slice of [from, to) of the edit. */
internal fun waveBars(source: PcmSource, edit: AudioEdit, from: Long, to: Long, count: Int): FloatArray {
    if (count <= 0) return FloatArray(0)
    val bars = FloatArray(count)
    val perBar = (to - from).toDouble() / count
    for (i in 0 until count) {
        val start = from + (i * perBar).toLong()
        val end = (from + ((i + 1) * perBar).toLong()).coerceAtLeast(start + 1)
        // Walk the parts of the original this slice covers.
        var at = start
        var peak = 0f
        while (at < end) {
            val sourceAt = edit.toSource(at)
            val first = (sourceAt / PcmSource.PEAK_FRAMES).toInt()
            if (first in source.peaks.indices && source.peaks[first] > peak) peak = source.peaks[first]
            at += PcmSource.PEAK_FRAMES
        }
        bars[i] = peak
    }
    return bars
}

/** "1:02.35" */
internal fun formatPrecise(ms: Long): String {
    val centis = ms.coerceAtLeast(0) / 10
    return String.format(Locale.ROOT, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100)
}

/** Where the "save as" screen starts: the song's folder on the phone's storage, if known. */
private fun initialFolderOf(track: Track): Uri? {
    if (track.sourceFolder != null) return runCatching {
        val docId = DocumentsContract.getDocumentId(Uri.parse(track.uri))
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId.substringBeforeLast('/', docId))
    }.getOrNull()
    if (track.folderKey.substringBefore('|') != "external_primary") return null
    val path = track.folderKey.substringAfter('|').trimEnd('/')
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
}

/** Android's "save as" screen for a .lrc file, opening in [initialFolder]. */
private class CreateLrcDocument(private val initialFolder: Uri?) : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            initialFolder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

internal const val BAR_PX = 3
private const val WINDOW_MS = 10_000L
private const val SKIP_MS = 5_000L
private const val NUDGE_MS = 50L
