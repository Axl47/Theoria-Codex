package com.theoriacodex.app.settings

import android.app.Application
import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.applock.AppLockViewModel
import com.theoriacodex.app.applock.appLockViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowToast

/** A device with no screen lock is the case where the row must neither lock nor trap its owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockRowTest {
    @get:Rule val composeRule = createComposeRule()

    @Before
    fun removeScreenLock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
            .setCanAuthenticate(false)
    }

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

    private fun showRow(): AppLockViewModel {
        lateinit var lock: AppLockViewModel
        composeRule.setContent {
            lock = appLockViewModel()
            MaterialTheme { AppLockRow() }
        }
        return lock
    }
}
