package com.theoriacodex.app.testing

import androidx.compose.ui.test.junit4.ComposeTestRule

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

fun ComposeTestRule.waitUntilWithoutIdling(
    timeoutMillis: Long = 10_000L,
    conditionDescription: String? = null,
    condition: () -> Boolean,
) {
    if (conditionDescription == null) {
        waitUntil(timeoutMillis = timeoutMillis, condition = condition)
    } else {
        waitUntil(conditionDescription = conditionDescription, timeoutMillis = timeoutMillis, condition = condition)
    }
}
