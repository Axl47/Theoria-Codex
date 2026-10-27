package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h600dp-mdpi")
class ViewerPostStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `compact strip centers its selected post with equal inner and edge gaps`() = checkSpacing(320.dp)
    @Test fun `wide strip spreads seven slots evenly across the viewport`() = checkSpacing(720.dp)

    private fun checkSpacing(width: Dp) {
        val image = ImageRef(null, "/missing/strip.jpg", "image/jpeg")
        val posts = (0..6).map { testPost(sourcePostId = "strip-$it").copy(preview = image) }
        compose.setContent {
            MaterialTheme { ViewerPostStrip(posts, 3, {}, Modifier.width(width).testTag("strip")) }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot
        val cells = posts.map { compose.onNodeWithContentDescription("Open post ${it.id.sourcePostId}").fetchSemanticsNode().boundsInRoot }
        assertEquals(viewport.center.x, cells[3].center.x, 1f)
        val edgeGap = cells.first().left - viewport.left
        cells.zipWithNext().forEach { (left, right) -> assertEquals(edgeGap, right.left - left.right, 1.5f) }
        assertEquals(edgeGap, viewport.right - cells.last().right, 1.5f)
    }
}
