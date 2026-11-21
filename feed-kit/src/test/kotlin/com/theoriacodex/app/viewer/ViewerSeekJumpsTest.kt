package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerSeekJumpsTest {
    @get:Rule val compose = createComposeRule()
    private val serial = mutableIntStateOf(0)
    private val delta = mutableLongStateOf(0L)
    private val enabled = mutableStateOf(true)
    private val jumps = mutableListOf<Long>()

    @Test fun `a handled jump is not replayed when the renderer rebinds or becomes active again`() {
        show()
        jump(1, 10_000L)
        compose.runOnIdle { assertEquals(listOf(10_000L), jumps) }
        enabled.value = false
        compose.waitForIdle()
        enabled.value = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(10_000L), jumps) }
        jump(2, -10_000L)
        compose.runOnIdle { assertEquals(listOf(10_000L, -10_000L), jumps) }
    }

    @Test fun `a jump made while disabled is dropped, not applied later`() {
        show()
        enabled.value = false
        jump(1, 10_000L)
        enabled.value = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<Long>(), jumps) }
    }

    @Test fun `a renderer mounted after a jump does not apply it`() {
        serial.intValue = 3
        delta.longValue = 10_000L
        show()
        compose.runOnIdle { assertEquals(emptyList<Long>(), jumps) }
    }

    private fun show() = compose.setContent {
        ViewerSeekJumps(serial.intValue, delta.longValue, { enabled.value }) { jumps += it }
    }

    private fun jump(nextSerial: Int, deltaMs: Long) {
        delta.longValue = deltaMs
        serial.intValue = nextSerial
        compose.waitForIdle()
    }
}
