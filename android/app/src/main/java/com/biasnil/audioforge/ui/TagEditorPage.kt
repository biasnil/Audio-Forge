package com.biasnil.audioforge.ui

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.tags.TagEditException
import com.biasnil.audioforge.tags.TagFileEditor
import kotlinx.coroutines.launch
import java.io.IOException

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
    val scope = rememberCoroutineScope()
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
    var askFolderAccess by remember { mutableStateOf(false) }
    var addingField by remember { mutableStateOf(false) }

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

    val performSave: () -> Unit = {
        val baseline = original
        val edited = fields
        if (baseline != null && edited != null && !saving) {
            saving = true
            saveError = null
            scope.launch {
                // Like the desktop: the playing song is released while its file is rewritten.
                val hold = container.playback.releaseFileForEdit(track.uri)
                val result = runCatching { container.tagEditor.write(track, baseline, edited.toMap()) }
                val updated = container.library.reloadTrack(track)
                container.playback.resumeAfterFileEdit(updated, hold)
                saving = false
                result.onSuccess { onBack() }
                    .onFailure { saveError = errorMessage(context, it) }
            }
        }
    }

    // Phone-library songs: Android's "Allow AudioForge to modify this audio file?" prompt.
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) performSave()
    }
    // Added folders from before tag editing existed: re-pick the folder once to grant write access.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        val folder = track.sourceFolder
        if (picked != null && folder != null) {
            if (container.library.grantFolderWriteAccess(picked, folder)) {
                performSave()
            } else {
                saveError = context.getString(R.string.tags_error_wrong_folder)
            }
        }
    }

    val startSave: () -> Unit = {
        val folder = track.sourceFolder
        when {
            folder == null -> {
                val request = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(track.uri)))
                writeRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
            !container.library.hasFolderWriteAccess(folder) -> askFolderAccess = true
            else -> performSave()
        }
    }

    val changed = original != null && fields != null && fields.orEmpty().toMap() != original
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
                            Text(
                                text = track.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                }
            }
        }
    }

    if (askFolderAccess) {
        AlertDialog(
            onDismissRequest = { askFolderAccess = false },
            title = { Text(stringResource(R.string.tags_folder_access_title)) },
            text = { Text(stringResource(R.string.tags_folder_access_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askFolderAccess = false
                    folderPicker.launch(track.sourceFolder?.let(Uri::parse))
                }) { Text(stringResource(R.string.tags_folder_access_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { askFolderAccess = false }) { Text(stringResource(R.string.cancel)) }
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

private fun errorMessage(context: android.content.Context, error: Throwable): String = when (error) {
    is TagEditException -> when (error.kind) {
        TagEditException.Kind.UnsupportedFormat -> context.getString(R.string.tags_error_unsupported)
        TagEditException.Kind.InvalidValue -> context.getString(
            R.string.tags_error_not_a_number,
            TagFileEditor.displayName(error.field.orEmpty()),
        )
        TagEditException.Kind.WriteFailed -> context.getString(R.string.tags_error_write)
    }
    is SecurityException -> context.getString(R.string.tags_error_permission)
    is IOException -> context.getString(R.string.tags_error_io)
    else -> context.getString(R.string.tags_error_write)
}
