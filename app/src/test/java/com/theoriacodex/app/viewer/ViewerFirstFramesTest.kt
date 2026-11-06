package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Samples real rendered pixels frame by frame, because these defects last only a frame or two. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerFirstFramesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: androidx.compose.runtime.MutableState<com.theoriacodex.app.viewer.state.ViewerUiState>

    /** Requests for [STALLED] never finish, so media can stay loading for as long as a test watches. */
    @Before fun stallImages() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Coil.setImageLoader(ImageLoader.Builder(context).components {
            add(Interceptor { chain -> if (chain.request.data == STALLED) awaitCancellation() else chain.proceed(chain.request) })
        }.build())
    }

    @After fun resetImages() = Coil.reset()

    private fun png(name: String, width: Int, height: Int, color: Color): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color.toArgb())
        val file = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "$name-${System.nanoTime()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    /** All posts are 2:1, so on this 400x800 screen their media is a band across the middle. */
    private fun post(id: String, preview: String, full: String): Post {
        val fullRef = ImageRef(null, full, "image/png")
        return Post(
            PostId(SourceKey.GELBOORU, id), ImageRef(null, preview, "image/png"), fullRef, listOf(fullRef), null, 1000, 500,
            emptyList(), emptyList(), null, null,
        )
    }

    private fun show(posts: List<Post>) {
        state = mutableStateOf(createViewerUiState(SESSION, posts, requiresResolution = { false }))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("root")) {
                    ViewerScreen(
                        uiState = state.value, creatorBrowsingSources = emptySet(),
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                    )
                }
            }
        }
    }

    /** Frames advance on the test clock, while decoding needs real time to finish between them. */
    private fun nextFrame(): PixelMap {
        compose.mainClock.advanceTimeByFrame()
        Thread.sleep(25)
        return compose.onNodeWithTag("root").captureToImage().toPixelMap()
    }

    @Test fun `a preview of another shape stays inside the media instead of filling the screen`() {
        show(listOf(post("tall", png("tall", 100, 300, Color.Blue), STALLED)))
        var frame = nextFrame()
        var attempts = 0
        while (frame[200, 400] != Color.Blue && attempts++ < 80) frame = nextFrame()
        assertEquals("The preview should load in the media band", Color.Blue, frame[200, 400])
        assertEquals("The preview must not paint above its media", Color.Black, frame[200, 200])
    }

    @Test fun `a swipe never dips to black between the preview and the full image`() {
        val posts = listOf(
            post("a", png("preview", 100, 50, Color.Blue), png("full", 1000, 500, Color.Green)),
            post("b", png("preview2", 100, 50, Color.Blue), png("full2", 1000, 500, Color.Green)),
        )
        show(posts)
        repeat(40) { nextFrame() }
        compose.onNodeWithTag("root").performTouchInput { down(Offset(width * 0.9f, height * 0.75f)) }
        compose.onNodeWithTag("root").performTouchInput { moveTo(Offset(width * 0.4f, height * 0.75f)) }
        var painted = false
        repeat(40) {
            // Sampled where the incoming page shows once it covers the right of the screen.
            val pixel = nextFrame()[320, 400]
            val visible = pixel == Color.Blue || pixel == Color.Green
            if (painted) assertTrue("The incoming page dipped to $pixel after it had painted", visible)
            painted = painted || visible
        }
        assertTrue("The incoming page should have painted", painted)
    }

    @Test fun `a resolving gallery keeps its cover on screen while its first page loads`() {
        val cover = post("gallery", png("cover", 100, 50, Color.Blue), STALLED).copy(media = emptyList(), full = null)
        show(listOf(cover))
        var attempts = 0
        while (nextFrame()[SAMPLE_X, SAMPLE_Y] != Color.Blue && attempts++ < 80) Unit
        val resolved = cover.copy(media = listOf(ImageRef(STALLED, null, "image/png")))
        state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(SESSION, resolved)).state
        repeat(40) {
            val pixel = nextFrame()[SAMPLE_X, SAMPLE_Y]
            assertEquals("The cover gave way to $pixel before the first page painted", Color.Blue, pixel)
        }
    }

    private companion object {
        val SESSION = ViewerSessionIdentity("frames")
        const val STALLED = "https://stalled.test/full.png"
        // Inside the 2:1 media band, clear of the centered loading indicator.
        const val SAMPLE_X = 80
        const val SAMPLE_Y = 400
    }
}
