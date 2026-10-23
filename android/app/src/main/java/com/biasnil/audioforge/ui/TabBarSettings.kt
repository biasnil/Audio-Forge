package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.TAB_BAR_SIZE
import com.biasnil.audioforge.data.effectiveTabBar
import com.biasnil.audioforge.data.tabBarEditList
import com.biasnil.audioforge.data.tabBarFromEditList

/**
 * Settings -> Tab bar: one list to drag. The tabs above the line are in the
 * tab bar (up to [TAB_BAR_SIZE], plus More); the rest are in More. Each row
 * also has a button to move it across without dragging, and tabs in More
 * can be hidden.
 */
@Composable
fun TabBarSettings(modifier: Modifier = Modifier) {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val all = remember { LibraryTab.entries.map { it.name } }
    val hidden = hiddenTabNames(settings.hiddenTabs)
    val bar = effectiveTabBar(all, settings.tabBar, hidden)
    val list = tabBarEditList(all, settings.tabBar, hidden)

    val listState = rememberLazyListState()
    val reorder = remember(listState) { ReorderState<String?>(listState) { it ?: LINE_KEY } }
    LaunchedEffect(settings.tabBar, settings.hiddenTabs) { reorder.clear() }
    val latestList = rememberUpdatedState(list)
    val shown = reorder.order ?: list
    val lineIndex = shown.indexOf(null)

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "intro") {
            Text(
                text = stringResource(R.string.tabbar_explanation, TAB_BAR_SIZE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader(stringResource(R.string.tabbar_in_bar, TAB_BAR_SIZE))
        }
        items(shown, key = { it ?: LINE_KEY }) { name ->
            val key = name ?: LINE_KEY
            val dragging = reorder.draggedKey == key
            val rowModifier = Modifier
                .graphicsLayer { if (dragging) translationY = reorder.offset }
                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            if (name == null) {
                // The line between the tab bar and More.
                Row(rowModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.tabbar_in_more))
                    HorizontalDivider(Modifier.weight(1f).padding(end = 16.dp))
                }
                return@items
            }
            val tab = LibraryTab.valueOf(name)
            val inBar = shown.indexOf(name) < lineIndex
            Row(
                modifier = rowModifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.DragHandle,
                    contentDescription = stringResource(R.string.tabbar_drag),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = reorder.handleModifier(key, current = { latestList.value }) { moved ->
                        save(store, moved, bar)
                    }.padding(12.dp),
                )
                Icon(tab.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(tab.title), modifier = Modifier.weight(1f))
                if (!inBar && tab.hideable) {
                    // Shown in More, or hidden altogether.
                    val visible = name !in hidden
                    Checkbox(
                        checked = visible,
                        onCheckedChange = { show ->
                            store.update {
                                it.copy(hiddenTabs = if (show) it.hiddenTabs - name else it.hiddenTabs + name)
                            }
                        },
                    )
                }
                if (inBar) {
                    IconButton(onClick = { save(store, list.filter { it != name } + name, bar) }, enabled = bar.size > 1) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.tabbar_remove))
                    }
                } else {
                    IconButton(
                        onClick = {
                            val rest = list.filter { it != name }
                            val at = rest.indexOf(null)
                            save(store, rest.take(at) + name + rest.drop(at), bar)
                        },
                        enabled = bar.size < TAB_BAR_SIZE,
                    ) {
                        Icon(AppIcons.Add, contentDescription = stringResource(R.string.tabbar_add))
                    }
                }
            }
        }
    }
}

/** Saves the tab bar from a rearranged list; a tab moved into the bar is shown again if it was hidden. */
private fun save(store: AppStore, list: List<String?>, previous: List<String>) {
    val bar = tabBarFromEditList(list, previous)
    store.update { it.copy(tabBar = bar, hiddenTabs = it.hiddenTabs - bar.toSet()) }
}

private const val LINE_KEY = "tabbar-line"
