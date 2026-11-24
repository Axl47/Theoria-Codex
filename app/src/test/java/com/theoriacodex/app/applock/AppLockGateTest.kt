package com.theoriacodex.app.applock

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.SteppedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockGateTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val io = SteppedDispatcher()

    @Test fun `with the lock on, nothing shows until the choice is read, and then the lock comes first`() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val lock = showGate(storedEnabled = true)

        compose.onNodeWithText(PROTECTED).assertDoesNotExist()
        compose.onNodeWithText("Unlock").assertDoesNotExist()
        assertTrue(lock.locked)

        io.drain()
        compose.onNodeWithText("Unlock").assertExists()
        compose.onNodeWithText(PROTECTED).assertDoesNotExist()
    }

    @Test fun `with the lock off, the content shows only once the choice is read`() {
        val lock = showGate(storedEnabled = false)

        compose.onNodeWithText(PROTECTED).assertDoesNotExist()
        assertTrue(lock.locked)

        io.drain()
        compose.onNodeWithText(PROTECTED).assertExists()
    }

    @Test fun `switching the lock off and then on stores on, even if the first write would be slower`() {
        val lock = AppLockViewModel(app, io)
        io.drain()
        shadowOf(Looper.getMainLooper()).idle()

        lock.update(false)
        lock.update(true)
        io.drainNewestFirst()
        shadowOf(Looper.getMainLooper()).idle()

        val next = AppLockViewModel(app, io)
        io.drain()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, next.enabled)
    }

    private fun showGate(storedEnabled: Boolean): AppLockViewModel {
        app.getSharedPreferences("app_lock", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("enabled", storedEnabled) }
        val lock = AppLockViewModel(app, io)
        compose.setContent { AppLockGate(lock) { BasicText(PROTECTED) } }
        return lock
    }

    private companion object {
        const val PROTECTED = "Protected content"
    }
}
