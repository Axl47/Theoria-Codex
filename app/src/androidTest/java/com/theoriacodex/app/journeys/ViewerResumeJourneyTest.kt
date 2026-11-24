package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerResumeJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        if (index != GALLERY) return post
        val pages = List(PAGES) { page -> solidImage("$index-$page", Color.HSVToColor(floatArrayOf(page * 360f / PAGES, 0.7f, 0.9f))) }
        return post.copy(preview = pages.first(), full = pages.first(), media = pages, mediaCount = PAGES)
    }

    @Test
    fun aGalleryReopenedAfterTheAppStopsResumesOnItsLastPage_adr_0215() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SourceKey.PIXIV), listOf("multi"), emptyList(), SortMode.POPULAR, null, null),
                RECENT_SEARCH,
            )
        }
        launch()
        showGallery()
        turnTo(RESUME_PAGE)

        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("page $RESUME_PAGE reached disk after the app stopped", pageOnDisk(RESUME_PAGE))

        coldGraphRelaunch()
        showGallery()
        waitFor("the reopened gallery resumes on page $RESUME_PAGE") { pageShown(RESUME_PAGE) }

        turnTo(RESUME_PAGE + 1)
        waitFor("page ${RESUME_PAGE + 1} is recorded") { recorded(RESUME_PAGE + 1) }
        pressSystemBack()
        waitFor("the Viewer closed") { !compose.onNodeWithTag("viewer-page-scrubber").isDisplayed() && compose.onNodeWithTag(searchCardTestTag(galleryPost().id)).isDisplayed() }
        assertTrue("page ${RESUME_PAGE + 1} reached disk after leaving the Viewer", pageOnDisk(RESUME_PAGE + 1))
    }

    private fun turnTo(page: Int) {
        val scrubber = compose.onNodeWithTag("viewer-page-scrubber")
        waitFor("the Viewer shows its page scrubber") { scrubber.isDisplayed() }
        scrubber.performSemanticsAction(SemanticsActions.SetProgress) { it(page - 1f) }
        waitFor("the Viewer shows page $page") { pageShown(page) }
    }

    private fun showGallery() {
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$RECENT_SEARCH"))
        val card = compose.onNodeWithTag(searchCardTestTag(galleryPost().id))
        waitFor("gallery card is displayed") { card.isDisplayed() }
        activate(card)
    }

    private fun pageShown(page: Int) = compose.onNodeWithContentDescription("Page $page of $PAGES").isDisplayed()

    private fun recorded(page: Int) =
        io { container.data.readingPositions.snapshot() }.any { it.postId == galleryPost().id && it.mediaNumber == page }

    private fun pageOnDisk(page: Int): Boolean {
        val deadline = System.currentTimeMillis() + DISK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val stored = io { FileBackedReadingPositionRepository(container.data.storageDirectory).snapshot() }
            if (stored.any { it.postId == galleryPost().id && it.mediaNumber == page }) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun galleryPost() = posts.getValue(SourceKey.PIXIV)[GALLERY]

    private companion object {
        const val GALLERY = 3
        const val PAGES = 8
        const val RESUME_PAGE = 5
        const val RECENT_SEARCH = "journey:resume"
        const val DISK_TIMEOUT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
