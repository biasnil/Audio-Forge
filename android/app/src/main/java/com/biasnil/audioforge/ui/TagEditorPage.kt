package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.DetectedGenre
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.biasnil.audioforge.tags.TagEditException
import com.biasnil.audioforge.tags.TagFileEditor
import java.io.IOException
import kotlinx.coroutines.launch

/** Ordered (field key, value) pairs; saved as "KEY\u0000value" strings so edits survive rotation. */
private val FieldListSaver = listSaver<List<Pair<String, String>>?, String>(
    save = { fields -> fields.orEmpty().map { (key, value) -> key + "\u0000" + value } },
    restore = { saved ->
        saved.takeIf { it.isNotEmpty() }?.map { entry -> entry.substringBefore('\u0000') to entry.substringAfter('\u0000') }
    },
)

/**
 * The desktop's manual tag editor: one row per field, core fields (Title,
 * Artist, Album...) always shown, "+ Add field" for anything else the
 * format supports, X to delete a field. Save writes into the music file;
 * Android asks first for songs from the phone's library, and added folders
 * need write access granted once.
 */
@Composable
fun TagEditorPage(trackUri: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val track: Track? = remember(trackUri) {
        container.library.trackFor(trackUri)
            ?: container.playback.state.value.current?.takeIf { it.uri == trackUri }
    }
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // What the file had when the editor opened (the baseline for "what changed").
    var original by remember { mutableStateOf<Map<String, String>?>(null) }
    var addableKeys by remember { mutableStateOf(emptyList<String>()) }
    var fields by rememberSaveable(stateSaver = FieldListSaver) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var addingField by remember { mutableStateOf(false) }
    // The file's name without its extension; only phone-library songs can be renamed here
    // (an added folder's file would get a new identity, and lose its playlists and stats).
    val extension = track.fileName.substringAfterLast('.', "")
    val originalBase = track.fileName.substringBeforeLast('.')
    val canRename = track.sourceFolder == null && track.fileName.isNotEmpty()
    var fileBase by rememberSaveable { mutableStateOf(originalBase) }
    var askLrcFolder by remember { mutableStateOf<LrcSync?>(null) }

    LaunchedEffect(trackUri) {
        try {
            val read = container.tagEditor.read(track)
            original = read.values
            addableKeys = read.addableKeys
            if (fields == null) fields = read.values.toList()
        } catch (e: Exception) {
            loadError = errorMessage(context, e)
        }
    }

    val writeFile = rememberTrackFileWriter()
    val trackActions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val newBase = safeFileName(fileBase).ifEmpty { originalBase }
    val renamed = canRename && newBase != originalBase

    // After saving: the .lrc next to the song follows a new file name, title or artist.
    fun syncLrc(sync: LrcSync) {
        scope.launch {
            val lrc = withContext(Dispatchers.IO) { container.sidecarLyrics.find(track) }
            when {
                lrc != null -> {
                    withContext(Dispatchers.IO) { container.sidecarLyrics.update(track, lrc, sync.title, sync.artist, sync.newBase) }
                    onBack()
                }
                container.sidecarLyrics.couldAskForFolder(track) && container.sidecarLyrics.mayAsk -> askLrcFolder = sync
                else -> onBack()
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        val sync = askLrcFolder
        askLrcFolder = null
        if (picked != null && sync != null) {
            container.sidecarLyrics.grantFolder(picked)
            syncLrc(sync)
        } else onBack()
    }

    val startSave: () -> Unit = {
        val baseline = original
        val edited = fields
        if (baseline != null && edited != null && !saving) {
            saving = true
            saveError = null
            val tagsChanged = edited.toMap() != baseline
            val newName = if (extension.isEmpty()) newBase else "$newBase.$extension"
            writeFile(track, {
                // Rename first: writing the tags makes Android re-read the file by its name, and
                // renaming while that's pending makes it drop the song from its index.
                if (renamed) container.tagEditor.renameFile(track, newName)
                if (tagsChanged) container.tagEditor.write(track, baseline, edited.toMap())
            }) { error ->
                saving = false
                when (error) {
                    null -> {
                        if (renamed) container.library.refresh()
                        val editedMap = edited.toMap()
                        val sync = LrcSync(
                            title = editedMap["TITLE"]?.takeIf { it != baseline["TITLE"] },
                            artist = editedMap["ARTIST"]?.takeIf { it != baseline["ARTIST"] },
                            newBase = newBase.takeIf { renamed },
                        )
                        if (sync.title != null || sync.artist != null || sync.newBase != null) syncLrc(sync) else onBack()
                    }
                    WriteCancelled -> Unit
                    else -> saveError = errorMessage(context, error)
                }
            }
        }
    }

    val changed = original != null && fields != null && (fields.orEmpty().toMap() != original || renamed)
    Scaffold(
        topBar = {
            DetailTopBar(stringResource(R.string.tags_title), onBack) {
                TextButton(onClick = startSave, enabled = changed && !saving) {
                    Text(stringResource(R.string.save))
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val currentFields = fields
            when {
                loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    CenteredText(loadError.orEmpty())
                }
                currentFields == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CenteredText(stringResource(R.string.tags_loading))
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(track, decodeSize = 72.dp, modifier = Modifier.size(72.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                if (canRename) {
                                    OutlinedTextField(
                                        value = fileBase,
                                        onValueChange = { fileBase = it },
                                        label = { Text(stringResource(R.string.tags_file_name)) },
                                        suffix = { if (extension.isNotEmpty()) Text(".$extension") },
                                        singleLine = true,
                                        enabled = !saving,
                                    )
                                } else {
                                    Text(
                                        text = track.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { trackActions.changeCover(track) }, enabled = !saving) {
                                    Text(stringResource(R.string.cover_change))
                                }
                            }
                        }
                    }
                    item {
                        FindMissingInfoSection(
                            fields = currentFields,
                            enabled = !saving,
                            onFill = { fields = it },
                            onUseCover = { bytes ->
                                scope.launch {
                                    val image = try {
                                        container.tagEditor.prepareCoverBytes(bytes)
                                    } catch (e: Exception) {
                                        trackActions.showMessage(context.getString(R.string.cover_error_image))
                                        return@launch
                                    }
                                    writeFile(track, { container.tagEditor.writeCover(track, image) }) { error ->
                                        when (error) {
                                            null -> trackActions.showMessage(context.getString(R.string.cover_changed))
                                            WriteCancelled -> Unit
                                            else -> saveError = errorMessage(context, error)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    items(currentFields, key = { it.first }) { (key, value) ->
                        TagFieldRow(
                            key = key,
                            value = value,
                            removable = key !in TagFileEditor.CORE_KEYS,
                            enabled = !saving,
                            onValueChange = { newValue ->
                                fields = currentFields.map { if (it.first == key) key to newValue else it }
                            },
                            onRemove = { fields = currentFields.filterNot { it.first == key } },
                        )
                    }
                    item {
                        TextButton(onClick = { addingField = true }, enabled = !saving, modifier = Modifier.padding(8.dp)) {
                            Text(stringResource(R.string.tags_add_field))
                        }
                    }
                    // The analysis can fill the BPM and Key tags (when this format has them).
                    val analysis = container.analysis.analysisFor(track)
                    val canHold = { key: String -> currentFields.any { it.first == key } || key in addableKeys }
                    if (analysis != null && (analysis.bpm != null || analysis.key != null) && (canHold("BPM") || canHold("KEY"))) {
                        item {
                            TextButton(
                                onClick = {
                                    var updated: List<Pair<String, String>> = currentFields
                                    fun set(key: String, value: String) {
                                        if (!canHold(key)) return
                                        updated = if (updated.any { it.first == key }) updated.map { if (it.first == key) key to value else it }
                                        else updated + (key to value)
                                    }
                                    analysis.bpm?.let { set("BPM", Math.round(it).toString()) }
                                    analysis.key?.let { set("KEY", it.tagValue) }
                                    fields = updated
                                },
                                enabled = !saving,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) { Text(stringResource(R.string.tags_fill_from_analysis)) }
                        }
                    }
                    // Genre detection can fill the GENRE tag: the subgenre if one stood out, else the broad genre.
                    val detected = container.genres.genreFor(track)?.takeIf { it.status == DetectedGenre.Status.OK }
                    if (detected?.label != null) {
                        item {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    text = stringResource(R.string.genre_detected, detected.label!!),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                if (canHold("GENRE")) {
                                    TextButton(
                                        onClick = {
                                            val value = detected.subgenre ?: detected.broad!!
                                            fields = if (currentFields.any { it.first == "GENRE" }) {
                                                currentFields.map { if (it.first == "GENRE") "GENRE" to value else it }
                                            } else currentFields + ("GENRE" to value)
                                        },
                                        enabled = !saving,
                                    ) { Text(stringResource(R.string.tags_fill_genre)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    askLrcFolder?.let { sync ->
        AlertDialog(
            onDismissRequest = {
                askLrcFolder = null
                onBack()
            },
            title = { Text(stringResource(R.string.lrc_access_title)) },
            text = { Text(stringResource(R.string.lrc_access_text)) },
            confirmButton = {
                TextButton(onClick = { pickFolder.launch(container.sidecarLyrics.folderOf(track)) }) {
                    Text(stringResource(R.string.lrc_access_choose))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        container.sidecarLyrics.mayAsk = false
                        askLrcFolder = null
                        onBack()
                    }) { Text(stringResource(R.string.lrc_access_never)) }
                    TextButton(onClick = {
                        askLrcFolder = null
                        onBack()
                    }) { Text(stringResource(R.string.lrc_access_skip)) }
                }
            },
        )
    }

    if (addingField) {
        val present = fields.orEmpty().map { it.first }.toSet()
        AddFieldDialog(
            keys = addableKeys.filter { it !in present },
            onDismiss = { addingField = false },
            onAdd = { key ->
                addingField = false
                fields = fields.orEmpty() + (key to "")
            },
        )
    }
}

/** What changed that the song's .lrc should follow (null = unchanged). */
private data class LrcSync(val title: String?, val artist: String?, val newBase: String?)

@Composable
private fun TagFieldRow(
    key: String,
    value: String,
    removable: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val removeButton: (@Composable () -> Unit)? = if (removable) {
        {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.tags_remove_field))
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(TagFileEditor.displayName(key)) },
        singleLine = key != "COMMENT" && key != "LYRICS",
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (TagFileEditor.isNumeric(key)) KeyboardType.Number else KeyboardType.Text,
        ),
        trailingIcon = removeButton,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Every other field this file's format supports, filterable -- there are over a hundred. */
@Composable
private fun AddFieldDialog(keys: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(keys, filter) {
        keys.map { it to TagFileEditor.displayName(it) }
            .filter { (_, name) -> name.contains(filter.trim(), ignoreCase = true) }
            .sortedBy { it.second }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_add_field)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.tags_add_field_filter)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first }) { (key, name) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(key) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** A message for a failed tag/cover read or write. */
fun errorMessage(context: android.content.Context, error: Throwable): String = when (error) {
    is TagEditException -> when (error.kind) {
        TagEditException.Kind.UnsupportedFormat -> context.getString(R.string.tags_error_unsupported)
        TagEditException.Kind.InvalidValue -> context.getString(
            R.string.tags_error_not_a_number,
            TagFileEditor.displayName(error.field.orEmpty()),
        )
        TagEditException.Kind.WriteFailed -> context.getString(R.string.tags_error_write)
    }
    is WrongFolderPicked -> context.getString(R.string.tags_error_wrong_folder)
    is SecurityException -> context.getString(R.string.tags_error_permission)
    is IOException -> context.getString(R.string.tags_error_io)
    else -> context.getString(R.string.tags_error_write)
}
