package com.theoriacodex.app.journeys

import com.theoriacodex.app.library.LibrarySection
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.fixtures.providerShapedPosts
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The device proof that source calls are safe from the main thread: "Open original post" resolves the link on the
 * shell's Compose scope, which has no thread switch of its own, so only the adapter's own switch keeps the real
 * Danbooru parse of the single-post response off the main thread.
 */
class SourceCallThreadJourneyTest : AppJourneyFixture() {
    override val providerShapedSources = setOf(SOURCE)
    override fun fixturePosts(media: ImageRef) = mapOf(
        SOURCE to providerShapedPosts(SOURCE, POST_COUNT, media)
            .map { if (it.id.sourcePostId == LINKING_POST) it.copy(originUrl = ORIGIN_URL) else it },
    )

    @get:Rule val recorder = SourceParseRecorder()

    /** Any failure to resolve opens the link in a browser; the test blocks and records that instead. */
    private val browserLaunches = CopyOnWriteArrayList<String>()
    private val browserBlocker = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val toBrowser = intent.selector?.hasCategory(Intent.CATEGORY_APP_BROWSER) == true ||
                intent.data?.scheme in setOf("http", "https")
            if (!toBrowser) return null
            browserLaunches += (intent.selector?.data ?: intent.data).toString()
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Before
    fun blockBrowser() {
        InstrumentationRegistry.getInstrumentation().addMonitor(browserBlocker)
    }

    @After
    fun unblockBrowser() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(browserBlocker)
    }

    @Test
    fun openOriginalPostParsesTheResolvedPostOffTheMainThread() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SOURCE), listOf("landscape"), emptyList(), SortMode.NEWEST, null, null),
                RECENT_SEARCH,
            )
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$RECENT_SEARCH"))
        val linkingPost = posts.getValue(SOURCE).single { it.id.sourcePostId == LINKING_POST }
        longPress(compose.onNodeWithTag(searchCardTestTag(linkingPost.id)))

        recorder.reset()
        activate(compose.onNodeWithText("Open original post"))
        val viewer = compose.onNodeWithContentDescription("Open post $RESOLVED_POST", useUnmergedTree = true)
        try {
            compose.waitUntil(conditionDescription = "Viewer or browser opened", timeoutMillis = 10_000) {
                viewer.isDisplayed() || browserLaunches.isNotEmpty()
            }
        } catch (_: ComposeTimeoutException) {
            // The assertions below say what was missing.
        }

        val client = container.registry.providerShaped.getValue(SOURCE)
        val postRequests = client.postRequests.toList()
        val parses = recorder.parses.get()
        val mainThreadParses = recorder.mainThreadParses.get()
        Log.i(TAG, "postRequests=$postRequests parses=$parses mainThreadParses=$mainThreadParses browser=$browserLaunches")
        // The menu resolves the post once; the Viewer then refreshes it through its own switch.
        assertEquals("Single-post requests", setOf(RESOLVED_POST), postRequests.toSet())
        assertTrue("No provider response was parsed", parses >= 1)
        assertEquals("Provider responses parsed on the main thread", 0, mainThreadParses)
        assertEquals("Fell back to the browser", emptyList<String>(), browserLaunches.toList())
        assertTrue("The Viewer does not show post $RESOLVED_POST", viewer.isDisplayed())
    }

    private companion object {
        const val TAG = "SourceCallJourney"
        val SOURCE = SourceKey.DANBOORU
        const val POST_COUNT = 3
        const val LINKING_POST = "3"
        const val RESOLVED_POST = "1"
        const val ORIGIN_URL = "https://danbooru.donmai.us/posts/$RESOLVED_POST"
        const val RECENT_SEARCH = "journey:original-post"
    }
}
