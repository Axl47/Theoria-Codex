package com.theoriacodex.app.journeys

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test

class LibraryPullToRefreshJourneyTest : AppJourneyFixture() {
    private val uploads = CopyOnWriteArrayList<Post>()

    override fun fixturePosts(media: ImageRef): Map<SourceKey, List<Post>> {
        val fixtures = super.fixturePosts(media)
        uploads.addAll(fixtures.getValue(SourceKey.PIXIV))
        return fixtures + (SourceKey.PIXIV to uploads)
    }

    @Test
    fun pullingTheSavedPageAndTheSearchesCollectionShowsNewUploads_adr_0225() {
        val query = Query(QueryMode.Source(SourceKey.PIXIV), listOf("landscape"), emptyList(), SortMode.NEWEST, null, null)
        io { container.data.savedSearches.save("Landscapes", RecentSearchEntry(query, "landscapes", 1)) }
        launch()
        library(LibrarySection.Saved)
        waitFor("the Searches shelf shows uploads") { uploads.any { shows(it.id) } }

        val onShelf = upload("journey-shelf", 10_000L)
        pull()
        waitFor("the pull brings the new upload onto the Searches shelf") { shows(onShelf.id) }
        waitFor("the pill leaves once the refresh ends") { compose.onAllNodesWithText("Checking").fetchSemanticsNodes().isEmpty() }

        activate(compose.onNodeWithText("Searches"))
        waitFor("Searches opens") { inCollection() && shows(onShelf.id) }
        val inCollection = upload("journey-collection", 20_000L)
        pull()
        waitFor("the pull brings the new upload into Searches") { shows(inCollection.id) }
    }

    private fun upload(id: String, createdAt: Long): Post =
        uploads.last().copy(id = PostId(SourceKey.PIXIV, id), createdAtEpochMs = createdAt).also { uploads.add(0, it) }

    private fun pull() {
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.45f, endY = height * 0.9f, durationMillis = 400) }
    }

    private fun inCollection() = compose.onAllNodesWithTag("codex-chip-row").fetchSemanticsNodes().isNotEmpty()

    private fun shows(id: PostId) = compose.onAllNodesWithTag(searchCardTestTag(id)).fetchSemanticsNodes().isNotEmpty()
}
