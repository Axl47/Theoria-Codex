package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.transition.CrossfadeTransition
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
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
class ViewerMediaOverviewGridTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetCoil() = Coil.reset()

    @Test fun `tiles load thumbnails at once and originals only after resting on screen`() {
        val loads = installHangingLoader()
        // Page 1 has no separate thumbnail, so its one image is the original.
        val items = List(4) { index ->
            val thumbnail = if (index == 1) "/full/1.jpg" else "/thumb/$index.jpg"
            ViewerMediaOverviewItem(index, ImageRef(null, "/full/$index.jpg", "image/jpeg"), ViewerMediaOverviewKind.STILL_IMAGE, "/full/$index.jpg", thumbnail)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { ViewerMediaOverviewGrid(testPost(), items, 0, {}, Modifier.size(360.dp, 640.dp)) }
        }

        compose.mainClock.advanceTimeBy(VIEWER_SHARP_SETTLE_MS - 100)
        compose.runOnIdle {
            assertEquals("started ${loads.started}", listOf("/full/1.jpg", "/thumb/0.jpg", "/thumb/2.jpg", "/thumb/3.jpg"), loads.started.sorted())
        }

        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertEquals("started ${loads.started}", listOf("/full/0.jpg", "/full/1.jpg", "/full/2.jpg", "/full/3.jpg"), loads.started.filter { "/full/" in it }.sorted())
            val original = loads.requests.single { it.data.toString() == "/full/0.jpg" }
            assertTrue(original.transitionFactory is CrossfadeTransition.Factory)
        }
    }
}
