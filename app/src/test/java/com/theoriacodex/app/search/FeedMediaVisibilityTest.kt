package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedMediaVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a visible caption does not retain demand for offscreen media`() {
        val post = testPost(
            preview = ImageRef(null, null, "image/jpeg"),
            full = null,
            width = 640,
            height = 360,
            title = "Visible caption",
        )
        val scroll = ScrollState(0)
        var mediaVisible = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 200.dp, height = 32.dp).clipToBounds()) {
                    Column(Modifier.verticalScroll(scroll)) {
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = null,
                            onClick = {},
                            onViewportChanged = { mediaVisible = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(searchMediaTestTag(post.id), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(mediaVisible) }

        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        compose.onNodeWithTag(searchCardTestTag(post.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(searchMediaTestTag(post.id), useUnmergedTree = true).assertIsNotDisplayed()
        compose.runOnIdle { assertFalse(mediaVisible) }

        compose.runOnIdle { runBlocking { scroll.scrollTo(0) } }
        compose.onNodeWithTag(searchMediaTestTag(post.id), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(mediaVisible) }
    }
}
