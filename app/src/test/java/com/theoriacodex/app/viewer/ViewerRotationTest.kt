package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerRotationTest {
    @get:Rule val compose = createComposeRule()
    private val rotation = ViewerRotationState()
    private val shown = mutableStateOf(true)
    private val selection = mutableStateOf(selection("a", WIDE, IMAGE))

    @Test fun `rotates once when the opening post resolves to a wide video, then holds`() {
        show(autoRotate = true)
        compose.runOnIdle { assertNull(rotation.held) }
        selection.value = selection("a", WIDE, VIDEO)
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
        selection.value = selection("b", TALL, VIDEO)
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
    }

    @Test fun `leaving the opening post cancels the automatic rotation, and the button rotates instead`() {
        show(autoRotate = true)
        selection.value = selection("b", WIDE, VIDEO)
        compose.runOnIdle { assertNull(rotation.held) }
        compose.onNodeWithContentDescription("Rotate to fit").performClick()
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
    }

    @Test fun `with the setting off nothing rotates on its own, and leaving the Viewer releases a held rotation`() {
        selection.value = selection("a", WIDE, VIDEO)
        show(autoRotate = false)
        compose.runOnIdle { assertNull(rotation.held) }
        compose.onNodeWithContentDescription("Rotate to fit").performClick()
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
        shown.value = false
        compose.runOnIdle { assertNull(rotation.held) }
    }

    @Test fun `near-square videos and images without a held rotation offer nothing`() {
        selection.value = selection("a", SQUARE, VIDEO)
        show(autoRotate = true)
        compose.onNodeWithContentDescription("Rotate to fit").assertDoesNotExist()
        selection.value = selection("b", WIDE, IMAGE)
        compose.onNodeWithContentDescription("Rotate to fit").assertDoesNotExist()
    }

    private fun show(autoRotate: Boolean) {
        compose.setContent {
            if (shown.value) {
                CompositionLocalProvider(LocalViewerAutoRotateVideos provides autoRotate) {
                    Box(Modifier.fillMaxSize()) {
                        rememberViewerRotation(selection.value, rotation, inPictureInPicture = false)?.let { offer ->
                            ViewerRotateButton(offer, visible = true, bottomInset = 0.dp)
                        }
                    }
                }
            }
        }
    }

    private fun selection(id: String, size: Pair<Int, Int>, media: ImageRef): ViewerSelection {
        val post = Post(
            id = PostId(SourceKey.IWARA, id), preview = media, full = media, pageUrl = null,
            width = size.first, height = size.second, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
        return ViewerSelection(post, listOf(media), mediaIndex = 0, canPlayUgoira = false, overviewItems = emptyList())
    }

    private companion object {
        val WIDE = 1920 to 1080
        val TALL = 1080 to 1920
        val SQUARE = 1000 to 1000
        val IMAGE = ImageRef(url = "https://example.test/a.jpg", localPath = null, mime = "image/jpeg")
        val VIDEO = ImageRef(url = "https://example.test/a.mp4", localPath = null, mime = "video/mp4")
    }
}
