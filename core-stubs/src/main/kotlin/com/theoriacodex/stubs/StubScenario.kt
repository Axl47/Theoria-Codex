package com.theoriacodex.stubs

import com.theoriacodex.domain.model.SourceKey

data class SourceFailureConfig(
    val failSearch: Boolean,
    val failTrending: Boolean,
    val delayMs: Long,
)

data class StubScenario(
    val sources: Map<SourceKey, SourceFailureConfig>,
)

enum class StubScenarioPreset {
    NORMAL,
    PARTIAL_FAILURE,
    EMPTY_RESULTS,
    SLOW_NETWORK,
}
