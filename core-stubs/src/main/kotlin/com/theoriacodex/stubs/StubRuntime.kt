package com.theoriacodex.stubs

class StubRuntime(
    initialPreset: StubScenarioPreset = StubScenarioPreset.NORMAL,
) {
    @Volatile
    var preset: StubScenarioPreset = initialPreset
}

enum class StubScenarioPreset {
    NORMAL,
    PARTIAL_FAILURE,
    EMPTY_RESULTS,
    SLOW_NETWORK,
}
