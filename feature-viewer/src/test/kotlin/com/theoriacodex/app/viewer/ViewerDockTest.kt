package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerDockTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0

    private fun showDock(count: CommentCount?) = compose.setContent {
        ViewerDock(
            ViewerDockActions(
                liked = false, onToggleLike = {}, commentCount = count, onInfo = { opened++ },
                moreExpanded = false, onMoreExpandedChange = {}, moreItems = emptyList(), overview = null, playbackSettings = null,
            ),
        ) {}
    }

    @Test
    fun `Info carries the comment count and opens the info sheet`() {
        showDock(CommentCount.Exact(12))
        compose.onNode(hasContentDescription("Info, 12 comments")).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a post known only to have comments says so without a number`() {
        showDock(CommentCount.Some)
        compose.onNode(hasContentDescription("Info, comments")).assertExists()
    }

    @Test
    fun `Info without comments is just Info`() {
        showDock(null)
        compose.onNode(hasContentDescription("Info")).assertExists()
    }

    @Test
    fun `More always offers the full picker and offers picture-in-picture only when the media allows it`() {
        assertEquals(listOf("Save to…"), viewerMoreItems({}, null, emptyList()).map { it.label })
        assertEquals(listOf("Save to…", "Picture-in-picture"), viewerMoreItems({}, {}, emptyList()).map { it.label })
    }

    @Test
    fun `a focused comment opens the info sheet at the comments, and closing resets it`() {
        val actions = mutableListOf<ViewerAction>()
        val controls = ViewerControls({ actions += it }, ViewerScreenState(), false, true, false, false)

        assertEquals(InfoSheetTarget.Top, controls.infoSheetTarget)
        controls.openInfoSheetAtComments()
        assertEquals(InfoSheetTarget.Comments, controls.infoSheetTarget)
        assertEquals(listOf<ViewerAction>(ViewerAction.ShowPanel(ViewerPanel.INFO)), actions)
        controls.resetInfoSheetTarget()
        assertEquals(InfoSheetTarget.Top, controls.infoSheetTarget)
    }
}
