package com.theoriacodex.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TheoriaApplicationProcessPolicyTest {
    @Test fun `copy process skips all durable initialization only in explicitly enabled builds`() {
        assertFalse(shouldStartAppContainer(false, "com.theoriacodex", "com.theoriacodex:developerCopy", true))
        assertTrue(shouldStartAppContainer(false, "com.theoriacodex", "com.theoriacodex:developerCopy", false))
        assertTrue(shouldStartAppContainer(false, "com.theoriacodex", "com.theoriacodex", true))
        assertTrue(shouldStartAppContainer(false, "com.theoriacodex", "other:developerCopy", true))
        assertFalse(shouldStartAppContainer(false, "com.theoriacodex", null, true))
    }
    @Test
    fun `normal app process always starts durable readiness`() {
        assertTrue(
            shouldStartAppContainer(
                benchmarkFixturesEnabled = true,
                packageName = "com.theoriacodex",
                processName = "com.theoriacodex",
            ),
        )
    }

    @Test
    fun `benchmark fixture process skips durable readiness only when compile-time enabled`() {
        assertFalse(
            shouldStartAppContainer(
                benchmarkFixturesEnabled = true,
                packageName = "com.theoriacodex",
                processName = "com.theoriacodex:benchmarkFixture",
            ),
        )
        assertTrue(
            shouldStartAppContainer(
                benchmarkFixturesEnabled = false,
                packageName = "com.theoriacodex",
                processName = "com.theoriacodex:benchmarkFixture",
            ),
        )

        var containerStarts = 0
        startAppContainerIfAllowed(
            benchmarkFixturesEnabled = true,
            packageName = "com.theoriacodex",
            processName = "com.theoriacodex:benchmarkFixture",
            start = { containerStarts += 1 },
        )
        assertFalse(
            "Fixture process must not invoke the lazy container construction path",
            containerStarts > 0,
        )
    }

    @Test
    fun `unknown process identity fails toward normal initialization`() {
        assertTrue(
            shouldStartAppContainer(
                benchmarkFixturesEnabled = true,
                packageName = "com.theoriacodex",
                processName = null,
            ),
        )
    }
}
