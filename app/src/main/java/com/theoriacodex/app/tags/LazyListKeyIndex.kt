package com.theoriacodex.app.tags

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * Remembers where each keyed item sits in a lazy list whose content is added through [tracking], so the
 * list can scroll to an item by its key. Compose offers no lookup from key to index.
 */
internal class LazyListKeyIndex {
    private val indices = HashMap<Any, Int>()

    /** Where the item with [key] sits as of the last time the list's content was built, or null if it has none. */
    fun indexOf(key: Any): Int? = indices[key]

    /** Starts a fresh index; add the list's items through the returned scope, in order. */
    fun tracking(scope: LazyListScope): LazyListScope {
        indices.clear()
        return Tracking(scope)
    }

    private inner class Tracking(private val delegate: LazyListScope) : LazyListScope by delegate {
        private var next = 0

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            key?.let { indices[it] = next }
            next++
            delegate.item(key, contentType, content)
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            if (key != null) repeat(count) { indices[key(it)] = next + it }
            next += count
            delegate.items(count, key, contentType, itemContent)
        }
    }
}
