package com.theoriacodex.app.viewer

import androidx.compose.runtime.remember
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
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import coil.Coil
import org.junit.After
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

    @After fun resetImages() = Coil.reset()

    @Test fun `a missing image hands its recovery to the route instead of awaiting it in the page`() {
        installNotFoundLoader()
        val image = ImageRef("https://example.test/missing.jpg", null, "image/jpeg")
        val post = testPost(source = SourceKey.GELBOORU, full = image, media = listOf(image))
        val key = ViewerMediaKey(post.id, 0)
        val asked = mutableListOf<Post>()
        val recovery = object : ViewerMediaRecoveryRequest {
            override fun ask(post: Post, failedMedia: ImageRef) {
                asked += post
            }

            override suspend fun recoverStreams(post: Post, failedMedia: ImageRef): ViewerMediaRecovery =
                error("An image's recovery must not run in its page's coroutine, which leaving the page cancels")
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 500.dp)) {
                    ViewerMediaPage(
                        post = post, media = image, mediaIndex = 0,
                        controls = ViewerRendererControls(true, true, false, true, 1f, 0L, 0L),
                        events = viewerRendererEvents(null, key, 0L, {}, {}, {}),
                        transform = remember { mutableStateOf(ViewerTransformState()) }, onTransformChanged = {},
                        layout = ViewerMediaLayout(false, false, false),
                        videoOptions = ViewerVideoOptions(VideoQuality.AUTO, null, null, "Unavailable", false),
                        videoPositions = ViewerVideoPositions(), mediaError = null, imageSession = ViewerImageSession(),
                        pixivUgoiraClient = null, onAction = {}, onInteraction = {},
                        onToggleChrome = {}, onRequestMediaRecovery = recovery,
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(setOf(post), asked.toSet()) }
    }

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
                        post = post, media = video, mediaIndex = 0,
                        controls = ViewerRendererControls(true, true, false, true, 1f, 0L, 0L),
                        events = events, transform = remember { mutableStateOf(ViewerTransformState()) }, onTransformChanged = {},
                        layout = ViewerMediaLayout(false, false, false),
                        // Avoid hardware playback while exercising the actual video-page gesture layers.
                        videoOptions = ViewerVideoOptions(VideoQuality.AUTO, null, null, "Unavailable", false),
                        videoPositions = positions, mediaError = error.value, imageSession = imageSession,
                        pixivUgoiraClient = null, onAction = { actions += it }, onInteraction = {},
                        onToggleChrome = {}, onRequestMediaRecovery = null,
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
