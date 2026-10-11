package com.biasnil.audioforge.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drag-to-reorder for a LazyColumn whose rows are keyed by [keyOf] (those
 * keys must be unique and of a type no other row of the list uses). While
 * the finger is down the on-screen [order] runs ahead of the data; [finish]
 * hands back the new order to save.
 */
class ReorderState<T>(private val listState: LazyListState, private val keyOf: (T) -> String) {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    var order by mutableStateOf<List<T>?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var expectedIndex = -1

    fun start(key: String, items: List<T>) {
        draggedKey = key
        order = items
        offset = 0f
        expectedIndex = -1
    }

    fun dragBy(dy: Float) {
        val key = draggedKey ?: return
        val current = order ?: return
        offset += dy
        val layout = listState.layoutInfo
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = dragged.offset + offset
        val edge = dragged.size / 2f
        val scroll = when {
            top < layout.viewportStartOffset + edge -> -dragged.size / 4f
            top + dragged.size > layout.viewportEndOffset - edge -> dragged.size / 4f
            else -> 0f
        }
        if (scroll != 0f) offset += listState.dispatchRawDelta(scroll)

        if (expectedIndex >= 0 && dragged.index != expectedIndex) return
        val keys = current.map(keyOf)
        val center = dragged.offset + offset + dragged.size / 2f
        val target = layout.visibleItemsInfo.firstOrNull {
            it.key != key && it.key in keys && center >= it.offset && center < it.offset + it.size
        } ?: return
        val from = keys.indexOf(key)
        val to = keys.indexOf(target.key)
        if (from < 0 || to < 0) return
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        expectedIndex = dragged.index + (to - from)
        val newOffset = if (to > from) target.offset + target.size - dragged.size else target.offset
        offset -= newOffset - dragged.offset
    }

    /** The new order if the row moved, else null. The on-screen order stays until [clear]. */
    fun finish(original: List<T>): List<T>? {
        val final = order
        draggedKey = null
        offset = 0f
        if (final == null || final.map(keyOf) == original.map(keyOf)) {
            order = null
            return null
        }
        return final
    }

    /** Call when the saved data has changed (drops the on-screen order once nothing's dragged). */
    fun clear() {
        if (draggedKey == null) order = null
    }

    fun cancel() {
        draggedKey = null
        offset = 0f
        order = null
    }

    /** The drag handle's gesture. [current] is read when the drag starts and ends. */
    fun handleModifier(key: String, current: () -> List<T>, onMoved: (List<T>) -> Unit): Modifier =
        Modifier.pointerInput(key) {
            detectDragGestures(
                onDragStart = { start(key, current()) },
                onDrag = { change, amount ->
                    change.consume()
                    dragBy(amount.y)
                },
                onDragEnd = { finish(current())?.let(onMoved) },
                onDragCancel = { cancel() },
            )
        }
}
