package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerMediaErrorInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Retry receives real touches above video gestures for all recoverable error messages`() {
        val video = ImageRef("https://example.test/video.mp4", null, "video/mp4")
        val post = testPost(source = SourceKey.GELBOORU, full = video, media = listOf(video))
        val key = ViewerMediaKey(post.id, 0)
        val error = mutableStateOf(ViewerMediaError.Recoverable(key, "Post resolution timed out"))
        val actions = mutableListOf<ViewerAction>()
        val events = viewerRendererEvents(null, key, 0L, actions::add, {}, {})
        val positions = ViewerVideoPositions()
        val imageSession = ViewerImageSession()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 500.dp)) {
                    ViewerMediaPage(
                        post = post, media = video, mediaIndex = 0, mediaCount = 1,
                        controls = ViewerRendererControls(true, true, false, true, 1f, 0L, 0L),
                        events = events, transform = ViewerTransformState(), onTransformChanged = {},
                        layout = ViewerMediaLayout(false, false, false, false),
                        // Avoid hardware playback while exercising the actual video-page gesture layers.
                        videoOptions = ViewerVideoOptions(VideoQuality.AUTO, null, "Unavailable", false),
                        videoPositions = positions, mediaError = error.value, imageSession = imageSession,
                        pixivUgoiraClient = null, onAction = { actions += it }, onInteraction = {},
                        onToggleChrome = {}, onNavigate = {}, onRequestMediaRecovery = null,
                    )
                }
            }
        }
        listOf("Post resolution timed out", "Source error", "Could not play video").forEachIndexed { index, message ->
            compose.runOnIdle { error.value = ViewerMediaError.Recoverable(key, message) }
            compose.onNodeWithText("Retry").performTouchInput { click() }
            compose.runOnIdle { assertEquals(index + 1, actions.count { it == ViewerAction.RetryMedia }) }
        }
    }
}
