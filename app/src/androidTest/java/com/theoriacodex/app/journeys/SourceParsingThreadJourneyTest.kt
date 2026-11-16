package com.theoriacodex.app.journeys

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.fixtures.providerShapedPosts
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The gate for provider parsing on the main thread: Search pages through a source whose responses the real Danbooru
 * adapter parses, and the test fails if any parse ran on the main thread, or if too little parsing happened to tell.
 * It covers the Search path only.
 */
class SourceParsingThreadJourneyTest : AppJourneyFixture() {
    override val providerShapedSources = setOf(SOURCE)
    override fun fixturePosts(media: ImageRef) = mapOf(SOURCE to providerShapedPosts(SOURCE, POST_COUNT, media))

    @get:Rule val recorder = SourceParseRecorder()

    @Test
    fun searchParsesEveryProviderPageOffTheMainThread() {
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        activate(compose.onNode(hasContentDescription("Search source:", substring = true) and
            hasAnyAncestor(hasTestTag("Search composer"))))
        activate(compose.onNodeWithContentDescription("Booru"))
        activate(compose.onNodeWithContentDescription("Danbooru"))
        addSearchTag("landscape")
        activate(compose.onNodeWithTag("Search composer apply"))
        val client = container.registry.providerShaped.getValue(SOURCE)
        val firstCard = searchCardTestTag(posts.getValue(SOURCE).first().id)
        compose.waitUntil(conditionDescription = "First provider page is shown", timeoutMillis = 10_000) {
            compose.onNodeWithTag(firstCard).isDisplayed()
        }
        scrollToFirstVisibleCard(SOURCE, THIRD_PAGE_CARD, maxDrags = 80)

        val pages = client.pageRequests.toList()
        val parses = recorder.parses.get()
        val mainThreadParses = recorder.mainThreadParses.get()
        Log.i(TAG, "pages=$pages parses=$parses mainThreadParses=$mainThreadParses")
        assertTrue("Fewer than three provider pages were requested: $pages", pages.distinct().size >= 3)
        assertTrue("$parses parses for ${pages.size} responses", parses >= pages.size)
        assertEquals("Provider responses parsed on the main thread", 0, mainThreadParses)
        compose.onNodeWithTag(searchCardTestTag(posts.getValue(SOURCE)[THIRD_PAGE_CARD].id)).assertIsDisplayed()
    }

    private companion object {
        const val TAG = "SourceParsingJourney"
        val SOURCE = SourceKey.DANBOORU
        /** Three pages of the adapter's 40 posts: the third holds posts 80 to 99 in display order. */
        const val POST_COUNT = 100
        const val THIRD_PAGE_CARD = 82
    }
}
