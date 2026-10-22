package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerLinkedPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `pager keeps the linked page after a cover expands into a gallery`() {
        val cover = ImageRef(url = null, localPath = "/missing-cover.jpg", mime = "image/jpeg")
        val post = Post(
            id = PostId(SourceKey.NHENTAI, "552559"),
            preview = cover, full = cover, media = listOf(cover), mediaCount = 3,
            pageUrl = null, width = 800, height = 1_000,
            canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
        )
        val owner = ViewerViewModel(SavedStateHandle())
        owner.replaceSession(ViewerSession(
            posts = listOf(post),
            context = ViewerLaunchContext("deeplink", 0, ViewerStreamSource.SEARCH, 0),
            initialMediaIndex = 2,
        ))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = owner.state.collectAsState().value,
                    creatorBrowsingSources = emptySet(), onAction = owner::onAction,
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                    onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        compose.runOnIdle {
            owner.applyResolvedPost(post.copy(media = (1..3).map { cover.copy(localPath = "/missing-$it.jpg") }))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, owner.state.value.currentPage?.selectedMediaIndex) }
        compose.runOnIdle { owner.clearSession() }
    }
}
