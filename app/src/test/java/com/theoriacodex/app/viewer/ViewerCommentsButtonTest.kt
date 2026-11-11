package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.viewer.state.ViewerAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerCommentsButtonTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0

    private fun showChrome(count: CommentCount?) = compose.setContent {
        ViewerChrome(
            source = "Pixiv", indexLabel = null, onBack = {}, liked = false,
            actionsMenuExpanded = false, onActionsMenuExpandedChange = {},
            playbackSettingsExpanded = false, onPlaybackSettingsExpandedChange = {}, playbackSettingsEnabled = false,
            playbackRate = ViewerPlaybackRate.Normal, onPlaybackRateSelected = {},
            mediaOverviewAvailable = false, mediaOverviewVisible = false, onToggleMediaOverview = {}, onInfo = {},
            commentCount = count, onComments = { opened++ },
        )
    }

    @Test
    fun `the button names the count and opens the comments`() {
        showChrome(CommentCount.Exact(12))
        compose.onNode(hasContentDescription("12 comments")).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a post known only to have comments is labelled just Comments`() {
        showChrome(CommentCount.Some)
        compose.onNode(hasContentDescription("Comments")).assertExists()
    }

    @Test
    fun `no button without comments`() {
        showChrome(null)
        compose.onNode(hasContentDescription("Comments")).assertDoesNotExist()
        compose.onNode(hasContentDescription("0 comments")).assertDoesNotExist()
    }

    @Test
    fun `only the comments button opens the info sheet at the comments, and closing resets it`() {
        val actions = mutableListOf<ViewerAction>()
        val controls = ViewerControls({ actions += it }, ViewerScreenState(), false, true, false, false)

        assertEquals(InfoSheetTarget.Top, controls.infoSheetTarget)
        controls.openInfoSheetAtComments()
        assertEquals(InfoSheetTarget.Comments, controls.infoSheetTarget)
        assertEquals(listOf<ViewerAction>(ViewerAction.ShowMetadata), actions)
        controls.resetInfoSheetTarget()
        assertEquals(InfoSheetTarget.Top, controls.infoSheetTarget)
    }
}
