package com.theoriacodex.app.applock

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.SteppedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockRecentsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `recents screenshots stay blocked until the lock is read as off`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("app_lock", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("enabled", false) }
        val io = SteppedDispatcher()
        val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get()
        val lock = AppLockViewModel(app, io)

        activity.setContent { AppLockGate(lock) { BasicText("Protected content") } }
        compose.waitForIdle()
        assertEquals(listOf(false), activity.recentsScreenshots)

        io.drain()
        compose.waitForIdle()
        assertEquals(listOf(false, true), activity.recentsScreenshots)
    }
}

class RecordingActivity : ComponentActivity() {
    val recentsScreenshots = mutableListOf<Boolean>()

    override fun setRecentsScreenshotEnabled(enabled: Boolean) {
        recentsScreenshots += enabled
        super.setRecentsScreenshotEnabled(enabled)
    }
}
