package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.SmartField
import com.biasnil.audioforge.data.SmartOperator
import com.biasnil.audioforge.data.SmartPlaylist
import com.biasnil.audioforge.data.SmartRule
import com.biasnil.audioforge.data.SmartSort
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A smart playlist's songs (built-in or your own), with Play / Shuffle. They update as you play and love songs. */
@Composable
fun SmartPlaylistPage(id: String, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val stats by container.songData.stats.collectAsStateWithLifecycle()
    val state by container.playback.state.collectAsStateWithLifecycle()
    val trackActions = LocalTrackActions.current

    val smart = SmartPlaylist.BUILT_IN.firstOrNull { it.id == id } ?: settings.smartPlaylists.firstOrNull { it.id == id }
    if (smart == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // A random order is kept until the rules or library change (not reshuffled on every play count).
    val statsKey: Any = if (smart.sort == SmartSort.Random) Unit else stats
    val analysis by container.analysis.analysis.collectAsStateWithLifecycle()
    val tracks = remember(smart, library, statsKey, analysis) {
        smart.tracks(library, stats, analysisFor = container.analysis::analysisFor)
    }
    val name = smartName(smart)

    Scaffold(
        topBar = {
            DetailTopBar(name, onBack) {
                OverflowMenu { close ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, enabled = tracks.isNotEmpty(), onClick = {
                        close()
                        trackActions.playNext(tracks)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_add)) }, enabled = tracks.isNotEmpty(), onClick = {
                        close()
                        trackActions.addToQueue(tracks)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_add_to_title)) }, enabled = tracks.isNotEmpty(), onClick = {
                        close()
                        trackActions.addToPlaylist(tracks)
                    })
                    if (!smart.builtIn) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.smart_edit_rules)) }, onClick = {
                            close()
                            onOpenPage(Page.SmartPlaylistEdit(smart.id))
                        })
                    }
                }
            }
        },
        bottomBar = {
            MiniPlayerBar(state = state, onOpenNowPlaying = { onOpenPage(Page.NowPlaying) }, modifier = Modifier.navigationBarsPadding())
        },
    ) { innerPadding ->
        if (tracks.isEmpty()) {
            Box(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(smartIcon(smart), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.size(12.dp))
                    CenteredText(stringResource(if (smart.builtIn) R.string.smart_empty_builtin else R.string.smart_empty))
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding)) {
            item(key = "buttons") {
                Box(Modifier.padding(16.dp)) {
                    PlayShuffleButtons(
                        onPlay = { container.playback.playQueue(tracks, 0, from = name) },
                        onShuffle = { container.playback.playShuffled(tracks, from = name) },
                    )
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.uri == state.current?.uri,
                    onClick = { container.playback.playQueue(tracks, index, from = name) },
                    onLongClick = { trackActions.showMenu(track) },
                )
            }
        }
    }
}

/** Create ([id] = null) or edit a smart playlist's rules. */
@Composable
fun SmartPlaylistEditorPage(id: String?, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val existing = id?.let { wanted -> container.store.settings.value.smartPlaylists.firstOrNull { it.id == wanted } }
    var playlist by remember { mutableStateOf(existing ?: SmartPlaylist(name = "")) }
    val defaultName = stringResource(R.string.smart_playlist)

    Scaffold(
        topBar = {
            DetailTopBar(playlist.name.ifBlank { defaultName }, onBack) {
                TextButton(onClick = {
                    val saved = playlist.copy(name = playlist.name.trim().ifEmpty { defaultName })
                    container.library.saveSmartPlaylist(saved)
                    onBack()
                    if (existing == null) onOpenPage(Page.SmartPlaylistDetail(saved.id))
                }) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = playlist.name,
                onValueChange = { playlist = playlist.copy(name = it) },
                label = { Text(stringResource(R.string.smart_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.smart_match), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = playlist.matchAll,
                    onClick = { playlist = playlist.copy(matchAll = true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.smart_match_all)) }
                SegmentedButton(
                    selected = !playlist.matchAll,
                    onClick = { playlist = playlist.copy(matchAll = false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.smart_match_any)) }
            }

            Text(stringResource(R.string.smart_rules), style = MaterialTheme.typography.titleSmall)
            playlist.rules.forEach { rule ->
                RuleEditor(
                    rule = rule,
                    onChange = { changed -> playlist = playlist.copy(rules = playlist.rules.map { if (it.id == rule.id) changed else it }) },
                    onRemove = { playlist = playlist.copy(rules = playlist.rules.filterNot { it.id == rule.id }) },
                )
            }
            OutlinedButton(onClick = { playlist = playlist.copy(rules = playlist.rules + SmartRule()) }) {
                Icon(AppIcons.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.smart_add_rule))
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.smart_sort_by), modifier = Modifier.weight(1f))
                Dropdown(
                    label = smartSortLabel(playlist.sort),
                    options = SmartSort.entries,
                    optionLabel = { smartSortLabel(it) },
                    onPick = { playlist = playlist.copy(sort = it) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.smart_limit), modifier = Modifier.weight(1f))
                Switch(
                    checked = playlist.limit != null,
                    onCheckedChange = { on -> playlist = playlist.copy(limit = if (on) 25 else null) },
                )
            }
            playlist.limit?.let { limit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.smart_limit_songs, limit), modifier = Modifier.weight(1f))
                    TextButton(onClick = { playlist = playlist.copy(limit = (limit - 5).coerceAtLeast(5)) }) { Text("−") }
                    TextButton(onClick = { playlist = playlist.copy(limit = (limit + 5).coerceAtMost(1000)) }) { Text("+") }
                }
            }
            Text(
                text = stringResource(R.string.smart_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleEditor(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dropdown(
                label = smartFieldLabel(rule.field),
                options = SmartField.entries,
                optionLabel = { smartFieldLabel(it) },
                onPick = { field ->
                    val op = if (rule.op in field.operators) rule.op else field.operators.first()
                    onChange(rule.copy(field = field, op = op))
                },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.remove))
            }
        }
        Dropdown(
            label = smartOperatorLabel(rule.op),
            options = rule.field.operators,
            optionLabel = { smartOperatorLabel(it) },
            onPick = { onChange(rule.copy(op = it)) },
        )
        if (rule.field.kind != SmartField.Kind.Bool) {
            val number = rule.field.kind == SmartField.Kind.Number
            OutlinedTextField(
                value = rule.value,
                onValueChange = { onChange(rule.copy(value = it)) },
                placeholder = { Text(stringResource(if (number) R.string.smart_number else R.string.smart_text)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun <T> Dropdown(label: String, options: List<T>, optionLabel: @Composable (T) -> String, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    expanded = false
                    onPick(option)
                })
            }
        }
    }
}

@Composable
private fun smartFieldLabel(field: SmartField): String = stringResource(
    when (field) {
        SmartField.Title -> R.string.sort_title
        SmartField.Artist -> R.string.sort_artist
        SmartField.Album -> R.string.sort_album
        SmartField.Genre -> R.string.smart_field_genre
        SmartField.Year -> R.string.sort_year
        SmartField.PlayCount -> R.string.smart_field_play_count
        SmartField.LastPlayedDays -> R.string.smart_field_last_played
        SmartField.AddedDays -> R.string.smart_field_added
        SmartField.Loved -> R.string.smart_loved
        SmartField.Bpm -> R.string.sort_bpm
        SmartField.Key -> R.string.smart_field_key
        SmartField.Minutes -> R.string.smart_field_minutes
    }
)

@Composable
private fun smartOperatorLabel(op: SmartOperator): String = stringResource(
    when (op) {
        SmartOperator.Contains -> R.string.smart_op_contains
        SmartOperator.Is -> R.string.smart_op_is
        SmartOperator.IsNot -> R.string.smart_op_is_not
        SmartOperator.MoreThan -> R.string.smart_op_more
        SmartOperator.LessThan -> R.string.smart_op_less
        SmartOperator.Yes -> R.string.smart_op_yes
        SmartOperator.No -> R.string.smart_op_no
    }
)

@Composable
private fun smartSortLabel(sort: SmartSort): String = stringResource(
    when (sort) {
        SmartSort.Title -> R.string.sort_title
        SmartSort.MostPlayed -> R.string.smart_most_played
        SmartSort.RecentlyPlayed -> R.string.smart_recently_played
        SmartSort.RecentlyAdded -> R.string.smart_recently_added
        SmartSort.Random -> R.string.shuffle_random
        SmartSort.Bpm -> R.string.sort_bpm
    }
)

