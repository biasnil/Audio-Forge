package com.biasnil.audioforge.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R

@Composable
fun FoldersTab(onRequestAudioPermission: () -> Unit) {
    val container = LocalAppContainer.current
    val library = container.library
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val hasPermission by library.hasAudioPermission.collectAsStateWithLifecycle()

    // System folder picker; the chosen folder's read access is kept across restarts.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) library.addFolder(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader(stringResource(R.string.folders_phone_library))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.folders_include_phone_library),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.includePhoneLibrary,
                onCheckedChange = { include ->
                    library.setIncludePhoneLibrary(include)
                    if (include && !hasPermission) onRequestAudioPermission()
                },
            )
        }
        if (settings.includePhoneLibrary && !hasPermission) {
            Text(
                text = stringResource(R.string.library_needs_permission),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedButton(
                onClick = onRequestAudioPermission,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.allow_access)) }
        }

        HorizontalDivider(Modifier.padding(top = 16.dp))
        SectionHeader(stringResource(R.string.folders_added))
        if (settings.folders.isEmpty()) {
            Text(
                text = stringResource(R.string.folders_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        settings.folders.forEach { folder ->
            ListItem(
                headlineContent = {
                    Text(folderLabel(folder), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    TextButton(onClick = { library.removeFolder(folder) }) {
                        Text(stringResource(R.string.remove))
                    }
                },
            )
        }
        Button(
            onClick = { pickFolder.launch(null) },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp),
        ) {
            Icon(AppIcons.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.folders_add))
        }
        Text(
            text = stringResource(R.string.folders_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

/** "primary:Music/Rock" -> "Music/Rock"; the storage root -> "Internal storage" (or the volume's id). */
@Composable
private fun folderLabel(treeUri: String): String {
    val documentId = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    } catch (e: IllegalArgumentException) {
        return treeUri
    }
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':', "")
    return when {
        path.isNotEmpty() -> path
        volume == "primary" -> stringResource(R.string.folders_internal_storage)
        else -> volume
    }
}

