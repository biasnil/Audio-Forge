package com.biasnil.audioforge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.edit.AudioEdit
import com.biasnil.audioforge.edit.EditPreview
import com.biasnil.audioforge.edit.EditSaver
import com.biasnil.audioforge.edit.Gap
import com.biasnil.audioforge.edit.PcmSource
import com.biasnil.audioforge.edit.WaveView
import com.biasnil.audioforge.edit.normalizeGainDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The song editor's Audio tab. A big waveform you work on directly: pinch
 * to zoom, swipe to scroll, tap to set the playhead, long-press and drag to
 * select (then drag the selection's edges to fine-tune). Below it, the
 * common tools (Play, Cut, Trim, Undo, Redo); More tools has the rest.
 * Nothing touches the song until Save: every change can be undone, and a
 * cut part shows as a marker that can be tapped to put it back.
 */
@Composable
internal fun AudioTab(track: Track, source: PcmSource, workDir: File) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember(source) { EditPreview(source, scope) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val playing by preview.playing.collectAsStateWithLifecycle()
    val position by preview.position.collectAsStateWithLifecycle()

    // Undo / redo: every edit is kept; [step] is the one in use.
    var history by remember(source) { mutableStateOf(listOf(AudioEdit.whole(source.frames))) }
    var step by remember(source) { mutableIntStateOf(0) }
    val edit = history[step]
    val minFrames = remember(source) { source.msToFrames(MIN_VIEW_MS) }
    var view by remember(source) { mutableStateOf(WaveView.whole(source.frames, minFrames)) }
    var selection by remember(source) { mutableStateOf<LongRange?>(null) }
    var pickedGap by remember(source) { mutableStateOf<Gap?>(null) }
    var moreTools by rememberSaveable { mutableStateOf(false) }
    var draggingVolume by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val gaps = remember(edit, source) { edit.gaps(source.frames) }

    fun duration(frames: Long) = formatSeconds(source.framesToMs(frames))

    fun undo() {
        if (step == 0) return
        preview.stop()
        step--
        view = view.forTotal(history[step].length)
        selection = null
        pickedGap = null
    }

    fun redo() {
        if (step == history.lastIndex) return
        preview.stop()
        step++
        view = view.forTotal(history[step].length)
        selection = null
        pickedGap = null
    }

    /** Applies [next] as a new step (dropping anything that was undone) and says what happened. */
    fun change(next: AudioEdit, said: String?, playheadAt: Long = position) {
        preview.stop()
        history = history.take(step + 1) + next
        step = history.lastIndex
        view = view.forTotal(next.length)
        selection = null
        pickedGap = null
        preview.seek(playheadAt.coerceIn(0, next.length))
        if (said != null) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar(said, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
            }
        }
    }

    // While playing, the view follows the playhead.
    LaunchedEffect(position, playing) { if (playing) view = view.showing(position) }

    val writeFile = rememberTrackFileWriter()
    fun save(replace: Boolean) {
        askSave = false
        preview.stop()
        saving = 0f
        scope.launch {
            val rendered = try {
                withContext(Dispatchers.IO) { EditSaver.renderTagged(context, track, source, edit, File(workDir, "out")) { saving = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = null
                message = context.getString(R.string.editor_save_failed)
                return@launch
            }
            if (replace) {
                writeFile(track, { withContext(Dispatchers.IO) { EditSaver.replace(context, track, rendered) } }) { error ->
                    saving = null
                    rendered.delete()
                    message = when (error) {
                        null -> context.getString(R.string.editor_replaced)
                        is WriteCancelled -> null
                        else -> context.getString(R.string.editor_save_failed)
                    }
                }
            } else {
                val folder = withContext(Dispatchers.IO) { EditSaver.saveCopy(context, track, rendered) }
                rendered.delete()
                saving = null
                message = if (folder != null) context.getString(R.string.editor_saved_copy, folder) else context.getString(R.string.editor_save_failed)
                if (folder != null) container.library.refresh()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // The whole song, with the part on screen marked: tap or drag to go there.
        Overview(
            source = source,
            edit = edit,
            view = view,
            playhead = position,
            onJump = { frame -> view = view.scrolled(frame - (view.start + view.frames / 2)) },
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            EditorWaveform(
                source = source,
                edit = edit,
                view = view,
                playhead = position,
                selection = selection,
                gaps = gaps,
                pickedGap = pickedGap,
                onView = { view = it },
                onTap = { frame ->
                    selection = null
                    pickedGap = null
                    preview.seek(frame)
                    if (playing) preview.play(edit, frame)
                },
                onSelect = {
                    selection = it
                    pickedGap = null
                },
                onGap = { gap ->
                    selection = null
                    pickedGap = gap
                },
                modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))
        }

        // Where things are, and precise alternatives to the gestures.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPrecise(source.framesToMs(position)) + " / " + formatTime(source.framesToMs(edit.length)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = { view = view.zoomed(1 / ZOOM_STEP, position) }, enabled = view.frames < view.total) {
                Icon(AppIcons.ZoomOut, contentDescription = stringResource(R.string.editor_zoom_out))
            }
            IconButton(onClick = { view = view.zoomed(ZOOM_STEP, position) }, enabled = view.frames > minFrames) {
                Icon(AppIcons.ZoomIn, contentDescription = stringResource(R.string.editor_zoom_in))
            }
        }
        val picked = selection
        val gap = pickedGap
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                gap != null -> {
                    Text(
                        stringResource(R.string.editor_gap, duration(gap.source.length)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = {
                        change(edit.restore(gap), context.getString(R.string.editor_did_restore, duration(gap.source.length)), playheadAt = gap.position)
                    }) { Text(stringResource(R.string.editor_restore)) }
                }
                picked != null -> Text(
                    stringResource(
                        R.string.editor_selected,
                        formatPrecise(source.framesToMs(picked.first)),
                        formatPrecise(source.framesToMs(picked.last)),
                        formatPrecise(source.framesToMs(picked.last - picked.first)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                else -> Text(
                    stringResource(R.string.editor_gesture_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Selecting without dragging: from the playhead.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val end = picked?.last?.takeIf { it > position } ?: edit.length
                selection = position..end
                pickedGap = null
            }) { Text(stringResource(R.string.editor_set_start)) }
            TextButton(onClick = {
                val start = picked?.first?.takeIf { it < position } ?: 0L
                selection = start..position
                pickedGap = null
            }) { Text(stringResource(R.string.editor_set_end)) }
            if (picked != null) {
                TextButton(onClick = { view = view.around(picked.first, picked.last) }) { Text(stringResource(R.string.editor_zoom_selection)) }
            }
        }

        // The common tools.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(
                        icon = if (playing) AppIcons.Pause else AppIcons.Play,
                        label = stringResource(if (playing) R.string.editor_stop else R.string.editor_play),
                        primary = true,
                        onClick = { if (playing) preview.stop() else preview.play(edit, picked?.first ?: position) },
                    )
                    ToolButton(AppIcons.Cut, stringResource(R.string.editor_cut), enabled = picked != null && picked.last > picked.first) {
                        picked?.let { change(edit.cut(it.first, it.last), context.getString(R.string.editor_did_cut, duration(it.last - it.first)), playheadAt = it.first) }
                    }
                    ToolButton(AppIcons.Trim, stringResource(R.string.editor_trim), enabled = picked != null && picked.last > picked.first) {
                        picked?.let { change(edit.keepOnly(it.first, it.last), context.getString(R.string.editor_did_trim, duration(it.last - it.first)), playheadAt = 0) }
                    }
                    ToolButton(AppIcons.Undo, stringResource(R.string.editor_undo), enabled = step > 0) { undo() }
                    ToolButton(AppIcons.Redo, stringResource(R.string.editor_redo), enabled = step < history.lastIndex) { redo() }
                    ToolButton(AppIcons.Tune, stringResource(R.string.editor_more), selected = moreTools) { moreTools = !moreTools }
                }

                // More tools: only when asked for.
                AnimatedVisibility(moreTools) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.editor_volume), style = MaterialTheme.typography.titleSmall)
                            InfoButton(stringResource(R.string.editor_volume_explanation), title = stringResource(R.string.editor_volume))
                            Spacer(Modifier.weight(1f))
                            Text(String.format(Locale.ROOT, "%+.1f dB", edit.gainDb), style = MaterialTheme.typography.labelLarge)
                        }
                        Slider(
                            value = edit.gainDb,
                            onValueChange = { db ->
                                val next = edit.copy(gainDb = (db * 2).roundToInt() / 2f)
                                // One undo step per drag, not one per tick.
                                if (draggingVolume) history = history.take(step) + next
                                else {
                                    history = history.take(step + 1) + next
                                    step = history.lastIndex
                                }
                                draggingVolume = true
                            },
                            onValueChangeFinished = {
                                draggingVolume = false
                                if (playing) preview.play(history[step], position)
                            },
                            valueRange = -AudioEdit.MAX_GAIN_DB..AudioEdit.MAX_GAIN_DB,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val db = normalizeGainDb(source.peakOf(edit))
                                change(edit.copy(gainDb = db), context.getString(R.string.editor_did_normalize, String.format(Locale.ROOT, "%+.1f dB", db)))
                            }) { Text(stringResource(R.string.editor_normalize)) }
                            OutlinedButton(onClick = {
                                change(AudioEdit.whole(source.frames), context.getString(R.string.editor_did_restore_all), playheadAt = 0)
                            }, enabled = edit.changed) { Text(stringResource(R.string.editor_restore_all)) }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.editor_original_safe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Button(onClick = { askSave = true }, enabled = edit.changed && saving == null) { Text(stringResource(R.string.editor_save)) }
                }
                saving?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }

    if (askSave) {
        val canReplace = EditSaver.canReplace(track)
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(stringResource(R.string.editor_save_title)) },
            text = { Text(stringResource(if (canReplace) R.string.editor_save_text else R.string.editor_save_text_copy_only)) },
            confirmButton = { TextButton(onClick = { save(replace = false) }) { Text(stringResource(R.string.editor_save_copy)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = false }) { Text(stringResource(R.string.cancel)) }
                    if (canReplace) {
                        TextButton(onClick = { save(replace = true) }) {
                            Text(stringResource(R.string.editor_save_replace), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.info_got_it)) } },
        )
    }
}

/** An icon with its name under it, so every tool says what it does. */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
        if (primary) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(icon, contentDescription = label)
            }
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Icon(icon, contentDescription = label)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/**
 * The waveform of [view]'s part of the edit, worked on with gestures:
 * pinch to zoom, swipe to scroll, tap to set the playhead (or pick a cut
 * marker), long-press and drag to select; the selection's edges can then
 * be dragged. Every touch shows (and long-press / edge drags buzz).
 */
@Composable
private fun EditorWaveform(
    source: PcmSource,
    edit: AudioEdit,
    view: WaveView,
    playhead: Long,
    selection: LongRange?,
    gaps: List<Gap>,
    pickedGap: Gap?,
    onView: (WaveView) -> Unit,
    onTap: (Long) -> Unit,
    onSelect: (LongRange?) -> Unit,
    onGap: (Gap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    var width by remember { mutableIntStateOf(0) }
    val bars = remember(source, edit, view.start, view.frames, width) {
        if (width == 0) FloatArray(0) else waveBars(source, edit, view.start, view.end, width / BAR_PX)
    }
    // The gesture handler outlives recompositions, so it reads the latest values.
    val currentView by rememberUpdatedState(view)
    val currentSelection by rememberUpdatedState(selection)
    val currentGaps by rememberUpdatedState(gaps)
    val currentOnView by rememberUpdatedState(onView)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnGap by rememberUpdatedState(onGap)

    val description = buildString {
        append(stringResource(R.string.editor_waveform_desc, formatPrecise(source.framesToMs(playhead))))
        selection?.let {
            append(' ')
            append(stringResource(R.string.editor_selected, formatPrecise(source.framesToMs(it.first)), formatPrecise(source.framesToMs(it.last)), formatPrecise(source.framesToMs(it.last - it.first))))
        }
    }
    val zoomInLabel = stringResource(R.string.editor_zoom_in)
    val zoomOutLabel = stringResource(R.string.editor_zoom_out)

    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .onSizeChanged { width = it.width }
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(zoomInLabel) { currentOnView(currentView.zoomed(ZOOM_STEP, playhead)); true },
                    CustomAccessibilityAction(zoomOutLabel) { currentOnView(currentView.zoomed(1 / ZOOM_STEP, playhead)); true },
                )
            }
            .pointerInput(Unit) {
                val edgeTouch = EDGE_TOUCH_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v = currentView
                    fun xOf(frame: Long) = (frame - v.start).toFloat() / v.frames * size.width
                    fun frameAt(x: Float) = (currentView.start + (x / size.width * currentView.frames).toLong()).coerceIn(0, currentView.total)

                    // On a selection edge: drag that edge.
                    val picked = currentSelection
                    if (picked != null) {
                        val nearStart = abs(down.position.x - xOf(picked.first)) < edgeTouch
                        val nearEnd = abs(down.position.x - xOf(picked.last)) < edgeTouch
                        if (nearStart || nearEnd) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val fixed = if (nearEnd) picked.first else picked.last
                            drag(down.id) { change ->
                                val now = frameAt(change.position.x)
                                currentOnSelect(minOf(fixed, now)..maxOf(fixed, now))
                                change.consume()
                            }
                            return@awaitEachGesture
                        }
                    }

                    // What is it: a tap, a swipe, a pinch, or a long press?
                    val slop = viewConfiguration.touchSlop
                    val kind = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result = -1
                        while (result < 0) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            result = when {
                                event.changes.count { it.pressed } > 1 -> PINCH
                                change == null || !change.pressed -> TAP
                                (change.position - down.position).getDistance() > slop -> SWIPE
                                else -> -1
                            }
                        }
                        result
                    } ?: LONG_PRESS

                    when (kind) {
                        TAP -> {
                            val x = down.position.x
                            val gap = currentGaps.minByOrNull { abs(xOf(it.position) - x) }
                            if (gap != null && abs(xOf(gap.position) - x) < edgeTouch) currentOnGap(gap) else currentOnTap(frameAt(x))
                        }
                        SWIPE, PINCH -> while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            var next = currentView
                            if (zoom != 1f) next = next.zoomed(zoom, frameAt(event.calculateCentroid().x))
                            next = next.scrolled((-pan.x / size.width * next.frames).toLong())
                            currentOnView(next)
                            event.changes.forEach { it.consume() }
                        }
                        else -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val anchor = frameAt(down.position.x)
                            currentOnSelect(anchor..anchor)
                            drag(down.id) { change ->
                                val now = frameAt(change.position.x)
                                currentOnSelect(minOf(anchor, now)..maxOf(anchor, now))
                                change.consume()
                            }
                            // A long press without dragging selects nothing.
                            if (currentSelection?.let { it.last == it.first } == true) currentOnSelect(null)
                        }
                    }
                }
            },
    ) {
        val frames = view.frames.coerceAtLeast(1)
        fun xOf(frame: Long) = (frame - view.start).toFloat() / frames * size.width
        val mid = size.height / 2

        // Time ruler: a tick every second (or 5, 10, 30, 60 when zoomed out), labelled ones taller.
        val tickMs = rulerStepMs(source.framesToMs(frames))
        var tickAt = (source.framesToMs(view.start) / tickMs + 1) * tickMs
        while (true) {
            val x = xOf(source.msToFrames(tickAt))
            if (x > size.width) break
            drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, 10.dp.toPx()), strokeWidth = 1.dp.toPx())
            tickAt += tickMs
        }

        selection?.let { range ->
            val left = xOf(range.first)
            val right = xOf(range.last)
            drawRect(colors.primary.copy(alpha = 0.22f), topLeft = Offset(left, 0f), size = Size(right - left, size.height))
            // Handles: a line and a round grip at each edge.
            for (x in listOf(left, right)) {
                drawLine(colors.primary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(colors.primary, radius = 8.dp.toPx(), center = Offset(x, size.height - 12.dp.toPx()))
            }
        }

        val barWidth = size.width / bars.size.coerceAtLeast(1)
        bars.forEachIndexed { i, level ->
            val h = (level * (mid - 14.dp.toPx())).coerceAtLeast(1f)
            drawRect(colors.primary.copy(alpha = 0.85f), topLeft = Offset(i * barWidth, mid - h), size = Size((barWidth - 1f).coerceAtLeast(1f), h * 2))
        }

        // Where something was cut: a dashed line with a grip, brighter when picked.
        for (gap in gaps) {
            val x = xOf(gap.position)
            if (x < -1 || x > size.width + 1) continue
            val picked = gap == pickedGap
            val color = if (picked) colors.error else colors.tertiary
            drawLine(
                color, Offset(x, 0f), Offset(x, size.height),
                strokeWidth = (if (picked) 3 else 2).dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
            drawCircle(color, radius = (if (picked) 9 else 7).dp.toPx(), center = Offset(x, 14.dp.toPx()))
        }

        if (playhead in view.start..view.end) {
            val x = xOf(playhead)
            drawLine(colors.onSurface, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(colors.onSurface, radius = 5.dp.toPx(), center = Offset(x, 4.dp.toPx()))
        }
    }
}

/** The whole edit, small, with [view]'s part outlined; tap or drag to move the view there. */
@Composable
private fun Overview(
    source: PcmSource,
    edit: AudioEdit,
    view: WaveView,
    playhead: Long,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var width by remember { mutableIntStateOf(0) }
    val bars = remember(source, edit, width) { if (width == 0) FloatArray(0) else waveBars(source, edit, 0, edit.length, width / BAR_PX) }
    val total = edit.length.coerceAtLeast(1)
    val currentOnJump by rememberUpdatedState(onJump)
    val overviewLabel = stringResource(R.string.editor_overview)
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceContainerLow)
            .onSizeChanged { width = it.width }
            .semantics { contentDescription = overviewLabel }
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnJump((down.position.x / size.width * total).toLong())
                    drag(down.id) { change ->
                        currentOnJump((change.position.x / size.width * total).toLong())
                        change.consume()
                    }
                }
            },
    ) {
        val mid = size.height / 2
        val barWidth = size.width / bars.size.coerceAtLeast(1)
        bars.forEachIndexed { i, level ->
            val h = (level * mid).coerceAtLeast(0.5f)
            drawRect(colors.onSurfaceVariant.copy(alpha = 0.6f), topLeft = Offset(i * barWidth, mid - h), size = Size((barWidth - 1f).coerceAtLeast(1f), h * 2))
        }
        val left = view.start.toFloat() / total * size.width
        val right = view.end.toFloat() / total * size.width
        drawRect(colors.primary.copy(alpha = 0.18f), topLeft = Offset(left, 0f), size = Size((right - left).coerceAtLeast(2f), size.height))
        drawRect(
            colors.primary, topLeft = Offset(left, 0f), size = Size((right - left).coerceAtLeast(2f), size.height),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
        )
        val x = playhead.toFloat() / total * size.width
        drawLine(colors.onSurface, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }
}

/** A ruler step that gives a tick every 60-ish pixels' worth: 1, 5, 10, 30 or 60 seconds. */
private fun rulerStepMs(visibleMs: Long): Long = when {
    visibleMs <= 15_000 -> 1_000
    visibleMs <= 60_000 -> 5_000
    visibleMs <= 150_000 -> 10_000
    visibleMs <= 600_000 -> 30_000
    else -> 60_000
}

/** "2.5 s" */
private fun formatSeconds(ms: Long): String = String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

private const val TAP = 0
private const val SWIPE = 1
private const val PINCH = 2
private const val LONG_PRESS = 3
private const val EDGE_TOUCH_DP = 24
private const val ZOOM_STEP = 2f
/** The closest zoom: half a second across the screen. */
private const val MIN_VIEW_MS = 500L
