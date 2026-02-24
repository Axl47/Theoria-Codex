package com.theoriacodex.stubs

class StubRuntime(
    initialPreset: StubScenarioPreset = StubScenarioPreset.NORMAL,
) {
    @Volatile
    var preset: StubScenarioPreset = initialPreset
}
