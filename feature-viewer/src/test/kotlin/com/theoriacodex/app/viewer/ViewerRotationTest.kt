package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.ViewerOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "port")
class ViewerRotationTest {
    @get:Rule val compose = createComposeRule()
    private val rotation = ViewerRotationState()
    private val chrome = mutableStateOf(true)
    private val lock = mutableStateOf<ViewerOrientation?>(null)
    private val selection = mutableStateOf(selection("a", WIDE))

    @Test fun `swiping across wide and tall posts never rotates on its own (ADR 0174)`() {
        show()
        listOf(selection("b", TALL), selection("c", WIDE, VIDEO), selection("d", TALL, VIDEO), selection("e", WIDE)).forEach {
            selection.value = it
            compose.runOnIdle { assertNull(rotation.held) }
        }
    }

    @Test fun `the rotate button shows with the controls even when the post already fits, naming its direction (ADR 0174)`() {
        selection.value = selection("a", SQUARE)
        show()
        compose.onNodeWithContentDescription("Rotate to landscape").assertExists()
        selection.value = selection("b", TALL)
        compose.onNodeWithContentDescription("Rotate to landscape").assertExists()
    }

    @Test fun `the rotate button leaves with the controls`() {
        show(chromeVisible = false)
        compose.onNodeWithContentDescription("Rotate to landscape").assertDoesNotExist()
    }

    @Test fun `a tap rotates once and the hold survives swiping (ADR 0174)`() {
        show()
        compose.onNodeWithContentDescription("Rotate to landscape").performClick()
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
        selection.value = selection("b", TALL)
        compose.runOnIdle { assertEquals(ViewerOrientation.LANDSCAPE, rotation.held) }
    }

    @Test fun `the lock pins the current orientation and hides rotate until unlocked (ADR 0174)`() {
        show()
        compose.onNodeWithContentDescription("Lock rotation").performClick()
        compose.runOnIdle { assertEquals(ViewerOrientation.PORTRAIT, lock.value) }
        compose.onNodeWithContentDescription("Rotate to landscape").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unlock rotation").performClick()
        compose.runOnIdle {
            assertNull(lock.value)
            assertNull(rotation.held)
        }
        compose.onNodeWithContentDescription("Rotate to landscape").assertExists()
    }

    @Test fun `unlocking returns to the rotation chosen before the lock (ADR 0174)`() {
        show()
        compose.onNodeWithContentDescription("Rotate to landscape").performClick()
        compose.onNodeWithContentDescription("Lock rotation").performClick()
        compose.onNodeWithContentDescription("Unlock rotation").performClick()
        compose.runOnIdle {
            assertNull(lock.value)
            assertEquals(ViewerOrientation.LANDSCAPE, rotation.held)
        }
    }

    @Test
    @Config(qualifiers = "land")
    fun `unlocking returns to a rotation to portrait too (ADR 0174)`() {
        selection.value = selection("a", TALL)
        show()
        compose.onNodeWithContentDescription("Rotate to portrait").performClick()
        compose.onNodeWithContentDescription("Lock rotation").performClick()
        compose.onNodeWithContentDescription("Unlock rotation").performClick()
        compose.runOnIdle {
            assertNull(lock.value)
            assertEquals(ViewerOrientation.PORTRAIT, rotation.held)
        }
    }

    private fun show(chromeVisible: Boolean = true) {
        chrome.value = chromeVisible
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                val offer = rememberViewerRotation(selection.value, rotation, inPictureInPicture = false, lock.value) { lock.value = it }
                ViewerRotationLockButton(selection.value, offer, chrome.value, suppressed = false, Alignment.BottomStart)
                ViewerRotateButton(selection.value, offer, chrome.value, suppressed = false, slot = 1)
            }
        }
    }

    private fun selection(id: String, size: Pair<Int, Int>, media: ImageRef = IMAGE): ViewerSelection {
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
