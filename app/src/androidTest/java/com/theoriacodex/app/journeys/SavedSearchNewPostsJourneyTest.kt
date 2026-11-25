package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import org.junit.Test

class SavedSearchNewPostsJourneyTest : AppJourneyFixture() {
    private val uploads = CopyOnWriteArrayList<Post>()

    override fun fixturePosts(media: ImageRef): Map<SourceKey, List<Post>> {
        val fixtures = super.fixturePosts(media)
        uploads.addAll(fixtures.getValue(SourceKey.PIXIV))
        return fixtures + (SourceKey.PIXIV to uploads)
    }

    @Test
    fun reopeningSavedSearchesAfterACheckFoundNewPostsShowsThemFirstAndClearsTheCount() {
        val query = Query(QueryMode.Source(SourceKey.PIXIV), listOf("landscape"), emptyList(), SortMode.NEWEST, null, null)
        val saved = io { container.data.savedSearches.save("Landscapes", RecentSearchEntry(query, "landscapes", 1)) }
        launch()
        waitFor("the launch new-posts check finishes", timeoutMillis = 15_000) { io { saved.id in newPosts().marks } }
        library(LibrarySection.Saved)
        activate(compose.onNodeWithText("Searches"))
        waitFor("Searches shows the first page's newest post") { inCollection() && shows(uploads[19].id) }
        activate(compose.onNodeWithContentDescription("Back"))
        waitFor("Searches closes") { !inCollection() }

        val fresh = uploads.last().copy(id = PostId(SourceKey.PIXIV, "journey-new"), createdAtEpochMs = 10_000L)
        val shown = uploads.take(20).map { it.id.markKey() }
        uploads.add(0, fresh)
        io {
            container.data.uiRestoreRepository.updateSavedSearchNewPosts { state ->
                state.copy(marks = mapOf(saved.id to NewPostMarks(shown, listOf(fresh.id.markKey()) + shown, System.currentTimeMillis())))
            }
        }
        waitFor("the Searches shelf shows 1 new") { compose.onAllNodesWithText("1 new").fetchSemanticsNodes().isNotEmpty() }

        activate(compose.onNodeWithText("Searches"))
        waitFor("reopened Searches shows the new post") { inCollection() && shows(fresh.id) }
        waitFor("the new post counts as seen") { io { newPosts().marks.getValue(saved.id).newPostCount == 0 } }
        waitFor("only the new post's card is marked new") { markedNew() == 1 }

        activate(compose.onNodeWithTag(searchCardTestTag(fresh.id)))
        waitFor("the Viewer opens the new post") { compose.onAllNodesWithContentDescription("Media actions").fetchSemanticsNodes().isNotEmpty() }
        pressSystemBack()
        waitFor("Searches is back") { inCollection() && shows(fresh.id) }
        waitFor("opening the post cleared its marker") { markedNew() == 0 }
    }

    private fun markedNew() = compose.onAllNodes(hasStateDescription("New"), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun hasStateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private suspend fun newPosts() = container.data.uiRestoreRepository.observeSavedSearchNewPosts().first()

    private fun inCollection() = compose.onAllNodesWithTag("codex-chip-row").fetchSemanticsNodes().isNotEmpty()

    private fun shows(id: PostId) = compose.onAllNodesWithTag(searchCardTestTag(id)).fetchSemanticsNodes().isNotEmpty()
}
