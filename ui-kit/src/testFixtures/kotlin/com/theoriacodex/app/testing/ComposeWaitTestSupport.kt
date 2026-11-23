package com.theoriacodex.app.testing

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits until [condition] holds while draining the main thread on each pass.
 *
 * Under Robolectric, bare [ComposeTestRule.waitUntil] advances Compose frames without running
 * work posted back to the main looper once the screen is idle. When [condition] reads a plain
 * variable or repository instead of querying the Compose tree (whose finders call [waitForIdle]),
 * background work returning via `Dispatchers.Main` can sit unread until the wait times out.
 *
 * Do not use this on screens that never go idle, such as looping animations with an advancing clock.
 */
fun ComposeTestRule.waitUntilSettled(
    timeoutMillis: Long = 10_000L,
    conditionDescription: String? = null,
    condition: () -> Boolean,
) {
    if (conditionDescription == null) {
        waitUntil(timeoutMillis = timeoutMillis) {
            waitForIdle()
            condition()
        }
    } else {
        waitUntil(conditionDescription = conditionDescription, timeoutMillis = timeoutMillis) {
            waitForIdle()
            condition()
        }
    }
}
