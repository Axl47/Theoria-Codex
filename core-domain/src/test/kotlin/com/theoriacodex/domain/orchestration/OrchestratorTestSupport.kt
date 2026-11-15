package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import kotlin.coroutines.EmptyCoroutineContext

/** An orchestrator whose provider calls stay on the test's dispatcher, so virtual time and call order stay deterministic. */
internal fun testOrchestrator(
    adaptersBySource: Map<SourceKey, SourceAdapter> = emptyMap(),
    sourceTimeoutMs: Long = 15_000L,
) = UnifiedSearchOrchestrator(adaptersBySource, sourceTimeoutMs, EmptyCoroutineContext)
