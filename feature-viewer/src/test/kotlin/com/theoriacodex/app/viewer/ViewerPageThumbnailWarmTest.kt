package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import coil.Coil
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerPageThumbnailWarmTest {
    @get:Rule val compose = createComposeRule()

    private val items = List(12) { index ->
        // Page 5 has no dedicated thumbnail: its poster stands in and is not warmed.
        val thumbnail = if (index == 5) "/poster/5.jpg" else "/thumb/$index.jpg"
        ViewerMediaOverviewItem(index, ImageRef(null, "/poster/$index.jpg", "image/jpeg"), ViewerMediaOverviewKind.STILL_IMAGE, "/poster/$index.jpg", thumbnail)
    }

    @After fun resetCoil() = Coil.reset()

    @Test fun `a rested post warms dedicated thumbnails nearest the current page first, eight at a time`() {
        val loads = installHangingLoader()
        compose.mainClock.autoAdvance = false
        compose.setContent { WarmViewerPageThumbnails(PostId(SourceKey.PIXIV, "1"), items, mediaIndex = 1) }
        compose.mainClock.advanceTimeBy(VIEWER_SHARP_SETTLE_MS - 50)
        compose.runOnIdle { assertTrue("warmed ${loads.warmed}", loads.warmed.isEmpty()) }

        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2, 3, 4, 6, 7, 8), loads.warmedPages())
            assertTrue(loads.warmed.first().data.toString().endsWith("/thumb/1.jpg"))
        }
    }

    @Test fun `turning pages within the post does not restart the warm`() {
        val loads = installHangingLoader()
        var page by mutableIntStateOf(0)
        compose.setContent { WarmViewerPageThumbnails(PostId(SourceKey.PIXIV, "1"), items, page) }
        rest()
        compose.runOnIdle { assertEquals(8, loads.warmed.size) }

        compose.runOnIdle { page = 9 }
        rest()
        compose.runOnIdle { assertEquals(8, loads.warmed.size) }
    }

    @Test fun `a single-page post warms nothing`() {
        val loads = installHangingLoader()
        compose.setContent { WarmViewerPageThumbnails(PostId(SourceKey.PIXIV, "1"), items.take(1), 0) }
        rest()
        compose.runOnIdle { assertTrue("warmed ${loads.warmed}", loads.warmed.isEmpty()) }
    }

    private fun rest() = compose.mainClock.advanceTimeBy(VIEWER_SHARP_SETTLE_MS + 50)

    private fun ImageLoadLog.warmedPages() =
        warmed.map { it.data.toString() }.filter { "/thumb/" in it }.map { it.substringAfter("/thumb/").substringBefore(".").toInt() }.sorted()
}
