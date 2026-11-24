package com.theoriacodex.app.journeys

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One picture listed by two sites: Search shows it once, and liking one copy hides the other, after a restart too. */
class SamePictureJourneyTest : AppJourneyFixture() {
    private val picture by lazy { patternImage("same-picture") }
    private var launched = false

    override fun customizePost(index: Int, post: Post): Post =
        if (index == 0) post.copy(preview = picture, full = picture) else post

    @Test
    fun aPictureOnTwoSitesIsOneCardAndItsLikedCopyHidesTheOtherAfterARestart() {
        val copies = listOf(SourceKey.PIXIV, SourceKey.GELBOORU).map { PostId(it, "journey-0") }
        search("journey:both", QueryMode.Unified)
        waitFor("a copy of the picture is shown", timeoutMillis = 15_000) { copies.any(::shows) }
        waitFor("Pixiv journey-1 copy is shown", timeoutMillis = 5_000) { shows(PostId(SourceKey.PIXIV, "journey-1")) }
        compose.waitForIdle()
        assertEquals("Both copies of one picture are cards", 1, copies.count(::shows))
        val liked = copies.single(::shows)
        val other = copies.single { it != liked }

        activate(compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(searchCardTestTag(liked))), useUnmergedTree = true))
        waitFor("liked copy is saved in likes") { io { liked in container.data.likesRepository.observeLikedPostIds("profile-main").first() } }
        // Saved with the hash its listing gave it, which a restart keeps.
        assertTrue(io { liked in container.data.visualSignatures.observePictureHashes().first() })

        coldGraphRelaunch()
        search("journey:other", QueryMode.Source(other.source))
        waitFor("search result for ${other.source} shows the other copy", timeoutMillis = 15_000) { shows(other) }
        activate(compose.onNodeWithContentDescription("Filter and sort"))
        activate(compose.onNodeWithText("Hide saved"))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        waitFor("other copy is hidden after Hide saved") { !shows(other) }
        waitFor("${other.source} journey-1 copy is shown", timeoutMillis = 5_000) { shows(PostId(other.source, "journey-1")) }
    }

    /** Runs a "multi" search recorded in Recents as [label], from History, as a user repeats a search. */
    private fun search(label: String, mode: QueryMode) {
        io { container.data.recentsRepository.recordSearch(Query(mode, listOf("multi"), emptyList(), SortMode.POPULAR, null, null), label) }
        if (!launched) launch().also { launched = true }
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$label"))
    }

    private fun shows(id: PostId) = compose.onAllNodesWithTag(searchCardTestTag(id)).fetchSemanticsNodes().isNotEmpty()
}
