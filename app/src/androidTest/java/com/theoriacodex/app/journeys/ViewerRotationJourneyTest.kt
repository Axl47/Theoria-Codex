package com.theoriacodex.app.journeys

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.theoriacodex.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerRotationJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post =
        if (index % 2 == 0) post.copy(width = 1920, height = 1080) else post.copy(width = 1080, height = 1920)

    @Test
    fun swipingNeverRotatesAndAChipRotationOutlastsALock() {
        openSearchPost(TALL, RECENT_SEARCH)
        waitFor("the Viewer follows the phone") { screen() == Configuration.ORIENTATION_PORTRAIT to ActivityInfo.SCREEN_ORIENTATION_USER }
        waitFor("rotate is offered on a post that already fits") { compose.onAllNodesWithContentDescription("Rotate to landscape").fetchSemanticsNodes().isNotEmpty() }

        activate(compose.onNodeWithContentDescription("Open post journey-$WIDE"))
        compose.waitForIdle()
        assertScreen(Configuration.ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER)

        activate(compose.onNodeWithContentDescription("Rotate to landscape"))
        waitFor("the Viewer turns to landscape") { screen().first == Configuration.ORIENTATION_LANDSCAPE }

        activate(compose.onNodeWithContentDescription("Open post journey-$NEXT_TALL"))
        compose.waitForIdle()
        assertScreen(Configuration.ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LOCKED)

        activate(compose.onNodeWithContentDescription("Lock rotation"))
        waitFor("the lock is shown as on") { compose.onAllNodesWithContentDescription("Unlock rotation").fetchSemanticsNodes().isNotEmpty() }
        assertScreen(Configuration.ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LOCKED)

        activate(compose.onNodeWithContentDescription("Unlock rotation"))
        waitFor("the lock shows as off") { compose.onAllNodesWithContentDescription("Lock rotation").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertScreen(Configuration.ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LOCKED)

        activate(compose.onNodeWithContentDescription("Rotate to portrait"))
        val followsPhone = if (systemAutoRotate()) ActivityInfo.SCREEN_ORIENTATION_USER else ActivityInfo.SCREEN_ORIENTATION_LOCKED
        waitFor("the chip turns back to portrait") { screen() == Configuration.ORIENTATION_PORTRAIT to followsPhone }
    }

    private fun systemAutoRotate(): Boolean {
        var on = false
        scenario.onActivity { on = Settings.System.getInt(it.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }
        return on
    }

    private fun screen(): Pair<Int, Int> {
        var shown = Configuration.ORIENTATION_UNDEFINED to ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { shown = it.resources.configuration.orientation to it.requestedOrientation }
        return shown
    }

    private fun assertScreen(orientation: Int, requested: Int) = assertEquals(orientation to requested, screen())

    private companion object {
        const val TALL = 1
        const val WIDE = 2
        const val NEXT_TALL = 3
        const val RECENT_SEARCH = "journey:rotation"
    }
}
