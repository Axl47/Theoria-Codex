package com.theoriacodex.app.tags

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostTagActionLazyLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `large tag menu renders a bounded viewport while metadata is pending`() {
        val lookupOnMain = AtomicBoolean(false)
        val lookups = AtomicInteger()
        val selections = mutableListOf<String>()
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "1"),
            preview = ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
            full = null,
            pageUrl = "https://example.test/post/1",
            width = null,
            height = null,
            canonicalTags = List(300) { "tag_$it" },
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 480.dp)) {
                    PostTagActionSection(
                        post = post,
                        header = { Text("Actions") },
                        footer = { Text("Cancel") },
                        tagVideoCountProvider = { _, _ ->
                            if (Looper.myLooper() == Looper.getMainLooper()) lookupOnMain.set(true)
                            lookups.incrementAndGet()
                            null
                        },
                        fetchTagVideoCounts = { _, _ -> awaitCancellation() },
                        onAddIncludeTerm = { selections += "include:${it.value}"; true },
                        onAddExcludeTerm = { true },
                        onRemoveIncludeTerm = { selections += "remove:${it.value}" },
                        onRemoveExcludeTerm = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Actions").assertIsDisplayed()
        compose.onNodeWithText("tag_299").assertDoesNotExist()
        compose.onAllNodesWithText("+")[0].performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("tag_299"))
        compose.onNodeWithText("tag_299").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Cancel"))
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Actions"))
        compose.onAllNodesWithText("+")[0].performClick()
        compose.waitUntil { lookups.get() == 300 }
        assertFalse(lookupOnMain.get())
        assertEquals(listOf("include:tag_0", "remove:tag_0"), selections)
    }
}
