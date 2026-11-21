package com.theoriacodex.app.lift

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.search.searchVideoTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiftedCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a lifted video post renders the card's video preview rather than a still`() {
        val post = testPost(media = listOf(ImageRef(url = "https://example.com/clip.mp4", localPath = null, mime = "video/mp4")))
        val bounds = Rect(0f, 0f, 200f, 200f)
        val session = LiftSession(touchSlopPx = 8f).also { it.start(post, bounds, Offset(100f, 100f)) }
        val lift = (session.phase as LiftPhase.Lifted).lift
        compose.setContent {
            MaterialTheme { Box { LiftedCopy(session, lift, settling = null, land = Animatable(0f)) } }
        }

        compose.onNodeWithTag(searchVideoTestTag(post.id), useUnmergedTree = true).assertExists()
    }
}
