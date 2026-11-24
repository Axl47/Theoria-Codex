package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import kotlin.coroutines.EmptyCoroutineContext

internal fun testOrchestrator(
    adaptersBySource: Map<SourceKey, SourceAdapter> = emptyMap(),
    sourceTimeoutMs: Long = 15_000L,
) = UnifiedSearchOrchestrator(adaptersBySource, sourceTimeoutMs, EmptyCoroutineContext)
