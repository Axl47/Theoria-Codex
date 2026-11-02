package com.theoriacodex.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared draft, drag thresholds and accessible moves for compact reorder lists. */
internal class ReorderListState<T>(initialItems: List<T>, private val key: (T) -> String) {
    var reorderMode by mutableStateOf(false)
    var reorderDraft by mutableStateOf(initialItems)
    var draggingId by mutableStateOf<String?>(null)
    var draggingIndex by mutableIntStateOf(-1)
    var dragOffsetY by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(1f)

    fun synchronize(items: List<T>) {
        if (!reorderMode) {
            reorderDraft = items
            return
        }
        val current = reorderDraft.mapNotNull { draft ->
            items.firstOrNull { item -> key(item) == key(draft) }
        }
        reorderDraft = current + items.filterNot { item ->
            current.any { existing -> key(existing) == key(item) }
        }
    }

    fun toggle(items: List<T>, onCommitReorder: (List<String>) -> Unit) {
        if (!reorderMode) {
            reorderDraft = items
            reorderMode = true
            return
        }
        onCommitReorder(reorderDraft.map(key))
        resetDrag()
        reorderMode = false
    }

    fun startDrag(id: String, index: Int) {
        draggingId = id
        draggingIndex = index
        dragOffsetY = 0f
    }

    fun move(id: String, offset: Int): Boolean {
        if (!reorderMode) return false
        val fromIndex = reorderDraft.indexOfFirst { key(it) == id }
        val toIndex = fromIndex + offset
        if (fromIndex !in reorderDraft.indices || toIndex !in reorderDraft.indices || offset == 0) return false
        resetDrag()
        reorderDraft = moveReorderItem(reorderDraft, fromIndex, toIndex)
        return true
    }

    fun drag(id: String, fallbackIndex: Int, deltaY: Float) {
        if (draggingId != id) return
        dragOffsetY += deltaY
        val currentIndex = draggingIndex.takeIf { it >= 0 } ?: fallbackIndex
        val threshold = itemHeightPx * 0.55f
        when {
            dragOffsetY >= threshold && currentIndex < reorderDraft.lastIndex -> {
                reorderDraft = moveReorderItem(reorderDraft, currentIndex, currentIndex + 1)
                draggingIndex = currentIndex + 1
                dragOffsetY -= itemHeightPx
            }
            dragOffsetY <= -threshold && currentIndex > 0 -> {
                reorderDraft = moveReorderItem(reorderDraft, currentIndex, currentIndex - 1)
                draggingIndex = currentIndex - 1
                dragOffsetY += itemHeightPx
            }
        }
    }

    fun resetDrag() {
        draggingId = null
        draggingIndex = -1
        dragOffsetY = 0f
    }
}

private fun <T> moveReorderItem(items: List<T>, from: Int, to: Int): List<T> =
    items.toMutableList().apply { add(to, removeAt(from)) }
