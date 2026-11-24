package com.theoriacodex.app.tags

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

internal class LazyListKeyIndex {
    private val indices = HashMap<Any, Int>()

    fun indexOf(key: Any): Int? = indices[key]

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
