package com.theoriacodex.app.testing

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

@OptIn(InternalComposeTracingApi::class)
class CompositionCounter internal constructor() : CompositionTracer {
    private val counts = mutableMapOf<String, Int>()

    override fun isTraceInProgress() = true

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val function = info.substringBefore(' ')
        synchronized(counts) { counts[function] = (counts[function] ?: 0) + 1 }
    }

    override fun traceEventEnd() = Unit

    fun count(function: String): Int = synchronized(counts) { counts[function] ?: 0 }

    fun reset() = synchronized(counts) { counts.clear() }
}

@OptIn(InternalComposeTracingApi::class)
fun <T> countCompositions(block: (CompositionCounter) -> T): T {
    val counter = CompositionCounter()
    Composer.setTracer(counter)
    return try {
        block(counter)
    } finally {
        Composer.setTracer(null)
    }
}

const val FEED_POST_CARD = "com.theoriacodex.app.ui.components.FeedPostCard"
