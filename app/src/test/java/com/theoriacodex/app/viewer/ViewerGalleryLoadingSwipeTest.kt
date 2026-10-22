package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerGalleryLoadingSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `swiping into a watched gallery resumes its saved page and preserves it on return`() =
        checkWatchedGallery(sparse = false)

    @Test fun `swiping into a watched sparse gallery retains the target until resolution without recording its cover`() =
        checkWatchedGallery(sparse = true)

    private fun checkWatchedGallery(sparse: Boolean) {
        val session = ViewerSessionIdentity("watched-gallery")
        val resolved = gallery().copy(media = (1..3).map(::image), mediaCount = 3)
        val first = gallery().copy(id = PostId(SourceKey.GELBOORU, "first"), full = image(0))
        val next = first.copy(id = PostId(SourceKey.GELBOORU, "next"))
        val watched = if (sparse) gallery().copy(mediaCount = 3) else resolved
        val recorded = mutableListOf<Int>()
        val state = mutableStateOf(createViewerUiState(
            session, listOf(first, watched, next), requiresResolution = { sparse && it.id == watched.id },
            initialMediaIndices = mapOf(watched.id to 2),
        ))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value, creatorBrowsingSources = emptySet(),
                    onAction = { state.value = reduceViewerState(state.value, it).state },
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                    onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                    onVisiblePostChanged = { post, number -> if (post.id == watched.id) recorded += number },
                    onVisibleMediaChanged = { post, number -> if (post.id == watched.id) recorded += number },
                )
            }
        }
        swipeNext()
        if (sparse) compose.runOnIdle {
            assertEquals(2, state.value.currentPage?.pendingMediaIndex)
            assertTrue(recorded.isEmpty())
            state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, resolved)).state
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, state.value.currentPageIndex)
            assertEquals(2, state.value.currentPage?.selectedMediaIndex)
            assertTrue(recorded.isNotEmpty())
            assertTrue(recorded.all { it == 3 })
        }
        swipeNext()
        compose.runOnIdle { assertEquals(2, state.value.currentPageIndex) }
        compose.onNodeWithContentDescription("Media actions").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, state.value.currentPageIndex)
            assertEquals(2, state.value.currentPage?.selectedMediaIndex)
        }
    }

    @Test fun `early swipe stays in sparse gallery then visits its pages before next post`() {
        val session = ViewerSessionIdentity("loading-gallery")
        val gallery = gallery()
        val next = gallery.copy(id = PostId(SourceKey.GELBOORU, "next"))
        val state = mutableStateOf(createViewerUiState(session, listOf(gallery, next), requiresResolution = { it == gallery }))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value, creatorBrowsingSources = emptySet(),
                    onAction = { state.value = reduceViewerState(state.value, it).state },
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                    onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        swipeNext()
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
            state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(
                session, gallery.copy(media = listOf(image(1), image(2)), mediaCount = 2),
            )).state
        }
        swipeNext()
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(1, state.value.currentPage?.selectedMediaIndex)
        }
        swipeNext()
        compose.runOnIdle {
            assertEquals(1, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
        }
    }

    @Test fun `known page count and failed resolution cannot turn a cover into gallery end`() {
        val session = ViewerSessionIdentity("known-gallery")
        val post = gallery().copy(full = image(1), media = listOf(image(1)), mediaCount = 4)
        var state = createViewerUiState(session, listOf(post), requiresResolution = { true })
        for (status in listOf(ViewerResolutionStatus.IDLE, ViewerResolutionStatus.RESOLVING, ViewerResolutionStatus.FAILED)) {
            val page = requireNotNull(state.currentPage).let { it.copy(resolution = it.resolution.copy(status = status)) }
            assertTrue(page.hasPendingGalleryMedia())
            assertEquals(4, page.swipeMediaCount())
            assertNull(viewerHorizontalSwipeTarget(0, 0, page.media.size, 2, 1,
                ViewerHorizontalSwipeDirection.Next, page.hasPendingGalleryMedia()))
            assertEquals(ViewerHorizontalSwipeTarget(0, 0), viewerHorizontalSwipeTarget(1, 0, page.media.size, 2, 1,
                ViewerHorizontalSwipeDirection.Previous, page.hasPendingGalleryMedia()))
            assertEquals(ViewerHorizontalSwipeDirection.Previous, viewerSwipeDirectionForSetting(
                ViewerHorizontalSwipeDirection.Next, page.swipeMediaCount(), true,
            ))
        }
        state = reduceViewerState(state, ViewerAction.ResolutionCompleted(session,
            post.copy(media = listOf(image(1), image(2)), mediaCount = 2),
        )).state
        assertFalse(requireNotNull(state.currentPage).hasPendingGalleryMedia())
        assertEquals(ViewerHorizontalSwipeTarget(1, 0), viewerHorizontalSwipeTarget(0, 1, 2, 2, 1,
            ViewerHorizontalSwipeDirection.Next, requireNotNull(state.currentPage).hasPendingGalleryMedia()))
    }

    private fun swipeNext() {
        compose.onNodeWithContentDescription("Media actions").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    private fun gallery() = Post(
        id = PostId(SourceKey.NHENTAI, "gallery"), preview = image(0), full = null,
        pageUrl = null, width = 800, height = 1_000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )

    private fun image(page: Int) = ImageRef(url = null, localPath = "/missing/gallery-$page.jpg", mime = "image/jpeg")
}
