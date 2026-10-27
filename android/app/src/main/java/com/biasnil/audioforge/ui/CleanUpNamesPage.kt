package com.biasnil.audioforge.ui

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.lookupNames
import kotlinx.coroutines.launch

/** A song whose name looks like a video's, and what it would become. */
private data class NameFix(val track: Track, val title: String, val artist: String)

/** Songs that smart title detection would tidy ("NSYNC - Bye Bye Bye (Lyrics)" by "7clouds" -> Bye Bye Bye by NSYNC). */
private fun nameFixes(tracks: List<Track>, rules: com.biasnil.audioforge.data.TitleRules): List<NameFix> =
    tracks.mapNotNull { track ->
        val names = lookupNames(track.title, track.artist, rules)
        if (!names.cleaned || names.title.isBlank()) return@mapNotNull null
        if (names.title == track.title.trim() && names.artist == track.artist.trim()) return@mapNotNull null
        NameFix(track, names.title, names.artist)
    }

/** How a run of fixes went. */
private data class CleanUpResult(val fixed: Int, val failed: Int, val readOnly: Int, val cancelled: Boolean = false)

/**
 * Settings -> Library -> Clean up song names: every song whose name looks
 * like a video title ("Artist - Title (Official Video)"), with what it
 * would become. Untick any, edit any, then Fix: the title and artist tags
 * are written into the files (Android asks once for songs in the phone's
 * library). Works on the phone only -- nothing is looked up online -- and
 * uses the same phrases as Download from link.
 */
@Composable
fun CleanUpNamesPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val found = rememberInBackground(tracks, settings.titleRules) { nameFixes(tracks, settings.titleRules) }
    /** Unticked songs, by URI (everything starts ticked). */
    var unticked by remember { mutableStateOf(emptySet<String>()) }
    /** Names the user changed here, by URI. */
    var edits by remember { mutableStateOf(emptyMap<String, Pair<String, String>>()) }
    var editing by remember { mutableStateOf<NameFix?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<CleanUpResult?>(null) }
    var waiting by remember { mutableStateOf<List<NameFix>>(emptyList()) }

    fun finalFix(fix: NameFix): NameFix = edits[fix.track.uri]?.let { (title, artist) -> fix.copy(title = title, artist = artist) } ?: fix

    fun apply(fixes: List<NameFix>, readOnly: Int) {
        scope.launch {
            var fixed = 0
            var failed = 0
            fixes.forEachIndexed { index, fix ->
                progress = index + 1 to fixes.size
                val track = fix.track
                val hold = container.playback.releaseFileForEdit(track.uri)
                val ok = runCatching {
                    val current = container.tagEditor.read(track).values
                    val edited = LinkedHashMap(current).apply {
                        put("TITLE", fix.title)
                        put("ARTIST", fix.artist)
                    }
                    container.tagEditor.write(track, current, edited)
                }.isSuccess
                val updated = container.library.reloadTrack(track)
                container.playback.resumeAfterFileEdit(updated, hold)
                if (ok) fixed++ else failed++
            }
            progress = null
            unticked = emptySet()
            edits = emptyMap()
            result = CleanUpResult(fixed, failed, readOnly)
        }
    }

    // One Android prompt for every phone-library song; added folders need their own write access.
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { answer ->
        val chosen = waiting
        waiting = emptyList()
        val folders = chosen.filter { it.track.sourceFolder != null }
        val writable = folders.filter { container.library.hasFolderWriteAccess(it.track.sourceFolder!!) }
        val readOnly = folders.size - writable.size
        if (answer.resultCode == Activity.RESULT_OK) {
            apply(chosen.filter { it.track.sourceFolder == null } + writable, readOnly)
        } else if (writable.isNotEmpty()) {
            apply(writable, readOnly)
        } else {
            result = CleanUpResult(0, 0, readOnly, cancelled = true)
        }
    }

    fun start(chosen: List<NameFix>) {
        result = null
        val phone = chosen.filter { it.track.sourceFolder == null }
        if (phone.isNotEmpty()) {
            waiting = chosen
            val request = MediaStore.createWriteRequest(context.contentResolver, phone.map { Uri.parse(it.track.uri) })
            writeRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            val writable = chosen.filter { container.library.hasFolderWriteAccess(it.track.sourceFolder!!) }
            apply(writable, chosen.size - writable.size)
        }
    }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.cleanup_title), onBack) }) { innerPadding ->
        val fixes = found
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item(key = "note") { SettingsNote(stringResource(R.string.cleanup_note), Modifier.padding(bottom = 8.dp)) }
            item(key = "status") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    progress?.let { (done, total) ->
                        Text(stringResource(R.string.cleanup_progress, done, total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { done.toFloat() / total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                    result?.let { r ->
                        val lines = buildList {
                            when {
                                r.cancelled -> add(stringResource(R.string.cleanup_cancelled))
                                r.failed == 0 -> add(pluralStringResource(R.plurals.cleanup_done, r.fixed, r.fixed))
                                else -> add(stringResource(R.string.cleanup_some_failed, r.fixed, r.failed))
                            }
                            if (r.readOnly > 0) add(pluralStringResource(R.plurals.cleanup_read_only, r.readOnly, r.readOnly))
                        }
                        Text(
                            lines.joinToString("\n"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
            when {
                fixes == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                fixes.isEmpty() -> item(key = "none") {
                    CenteredText(stringResource(R.string.cleanup_none), Modifier.fillMaxWidth().padding(32.dp))
                }
                else -> {
                    val chosen = fixes.filter { it.track.uri !in unticked }
                    item(key = "actions") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                pluralStringResource(R.plurals.cleanup_count, fixes.size, fixes.size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { start(chosen.map(::finalFix)) },
                                    enabled = chosen.isNotEmpty() && progress == null,
                                ) { Text(pluralStringResource(R.plurals.cleanup_fix, chosen.size, chosen.size)) }
                                TextButton(onClick = { unticked = emptySet() }, enabled = unticked.isNotEmpty()) {
                                    Text(stringResource(R.string.select_all))
                                }
                                TextButton(onClick = { unticked = fixes.map { it.track.uri }.toSet() }, enabled = chosen.isNotEmpty()) {
                                    Text(stringResource(R.string.select_none))
                                }
                            }
                        }
                    }
                    items(fixes, key = { it.track.uri }) { fix ->
                        val shown = finalFix(fix)
                        val ticked = fix.track.uri !in unticked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .toggleable(value = ticked, enabled = progress == null, role = Role.Checkbox) { on ->
                                    unticked = if (on) unticked - fix.track.uri else unticked + fix.track.uri
                                }
                                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(shown.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(shown.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.cleanup_was, fix.track.title, fix.track.artist),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { editing = shown }, enabled = progress == null) { Text(stringResource(R.string.cleanup_edit)) }
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.size(24.dp)) }
        }
    }

    editing?.let { fix ->
        var title by remember(fix.track.uri) { mutableStateOf(fix.title) }
        var artist by remember(fix.track.uri) { mutableStateOf(fix.artist) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.cleanup_edit)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.cleanup_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { val a = artist; artist = title; title = a }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.cleanup_swap))
                    }
                    OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.cleanup_field_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    edits = edits + (fix.track.uri to (title.trim() to artist.trim()))
                    editing = null
                }, enabled = title.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
