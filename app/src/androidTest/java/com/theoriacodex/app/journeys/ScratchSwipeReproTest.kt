package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Test

class ScratchSwipeReproTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        val color = Color.HSVToColor(floatArrayOf(index * 37f % 360f, 0.7f, 0.9f))
        val image = solidImage("p$index", color)
        return post.copy(preview = image, full = image, media = listOf(image), width = 800, height = 1000)
    }

    @Test
    fun idleInViewer() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SourceKey.PIXIV), listOf("multi"), emptyList(), SortMode.POPULAR, null, null), "journey:swipe",
            )
        }
        launch()
        tab("Recents")
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:journey:swipe"))
        val card = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[0].id))
        compose.waitUntil(10_000) { card.isDisplayed() }
        android.util.Log.d("SwipeRepro", "VIEWER_OPEN")
        val end = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(16)
            Thread.sleep(16)
        }
    }
}
