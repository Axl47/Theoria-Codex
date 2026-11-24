package com.theoriacodex.app.settings

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.applock.AppLockViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockRowTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

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
            assertEquals(false, lock.enabled)
        }
    }

    @Test
    fun `a lock left on after the screen lock was removed can still be switched off`() {
        val lock = showRow()
        composeRule.runOnIdle { lock.update(true) }

        composeRule.onNodeWithText("App lock").performClick()

        composeRule.runOnIdle { assertEquals(false, lock.enabled) }
    }

    @Test
    fun `with a screen lock set the lock stays on until its owner is confirmed`() {
        keyguard.setIsDeviceSecure(true)
        val lock = showRow()
        composeRule.runOnIdle { lock.update(true) }

        composeRule.onNodeWithText("App lock").performClick()

        composeRule.runOnIdle { assertEquals(true, lock.enabled) }
    }

    private fun showRow(): AppLockViewModel {
        val factory = viewModelFactory {
            initializer { AppLockViewModel(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined) }
        }
        val lock = ViewModelProvider(composeRule.activity, factory)[AppLockViewModel::class.java]
        composeRule.setContent { MaterialTheme { AppLockRow() } }
        return lock
    }
}
