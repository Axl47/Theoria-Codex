package com.theoriacodex.app.settings

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.applock.AppLockViewModel
import com.theoriacodex.app.applock.appLockViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** A device with no screen lock is the case where the row must neither lock nor trap its owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockRowTest {
    @get:Rule val composeRule = createComposeRule()

    private val keyguard =
        shadowOf(ApplicationProvider.getApplicationContext<Context>().getSystemService(KeyguardManager::class.java))

    @Before
    fun removeScreenLock() = keyguard.setIsDeviceSecure(false)

    @Test
    fun `without a screen lock the app lock stays off and says why`() {
        val lock = showRow()

        composeRule.onNodeWithText("App lock").performClick()

        composeRule.runOnIdle {
            assertEquals("Set a screen lock on this device first", ShadowToast.getTextOfLatestToast())
            assertFalse(lock.enabled)
        }
    }

    @Test
    fun `a lock left on after the screen lock was removed can still be switched off`() {
        val lock = showRow()
        composeRule.runOnIdle { lock.update(true) }

        composeRule.onNodeWithText("App lock").performClick()

        composeRule.runOnIdle { assertFalse(lock.enabled) }
    }

    @Test
    fun `with a screen lock set the lock stays on until its owner is confirmed`() {
        keyguard.setIsDeviceSecure(true)
        val lock = showRow()
        composeRule.runOnIdle { lock.update(true) }

        composeRule.onNodeWithText("App lock").performClick()

        composeRule.runOnIdle { assertTrue(lock.enabled) }
    }

    private fun showRow(): AppLockViewModel {
        lateinit var lock: AppLockViewModel
        composeRule.setContent {
            lock = appLockViewModel()
            MaterialTheme { AppLockRow() }
        }
        return lock
    }
}
