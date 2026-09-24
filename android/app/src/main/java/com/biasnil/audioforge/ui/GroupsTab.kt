package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.biasnil.audioforge.data.TrackGroup

/** Albums or Artists: one row per group, "(N tracks)" like the desktop lists. */
@Composable
fun GroupsTab(
    groups: List<TrackGroup>,
    unknownName: Int,
    onOpenGroup: (String) -> Unit,
    onRequestAudioPermission: () -> Unit,
) {
    if (groups.isEmpty()) {
        LibraryEmptyState(onRequestAudioPermission)
        return
    }
    LazyColumn {
        items(groups, key = { it.name }) { group ->
            ListItem(
                headlineContent = {
                    Text(
                        text = group.name.ifEmpty { stringResource(unknownName) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = { Text(trackCountText(group.tracks.size)) },
                modifier = Modifier.clickable { onOpenGroup(group.name) },
            )
        }
    }
}
