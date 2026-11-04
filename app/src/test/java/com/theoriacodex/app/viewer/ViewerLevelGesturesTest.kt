package com.theoriacodex.app.viewer

import android.app.Activity
import android.app.Application
import android.media.AudioManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.VideoQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerLevelGesturesTest {
    @get:Rule val compose = createComposeRule()
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private lateinit var levels: ViewerLevelControls
    private lateinit var audio: AudioManager
    private lateinit var activity: Activity
    private val layout = mutableStateOf(ViewerMediaLayout(false, false, false))
    private val transform = mutableStateOf(ViewerTransformState())
    private val controls = mutableStateOf(ViewerRendererControls(true, true, false, true, 1f, 0L, 0L))
    private val navigations = mutableListOf<ViewerHorizontalSwipeDirection>()
    private var chromeToggles = 0
    private var interactions = 0
    private var seekInteractions = 0
    private var dismissDrags = 0
    private var infoSwipes = 0

    @Before fun setup() {
        activity = activityController.setup().get()
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.5f }
        audio = activity.getSystemService(AudioManager::class.java)
        levels = ViewerLevelControls(activity, activity.window).apply { setEnabled(true) }
    }

    @After fun cleanup() {
        levels.setEnabled(false)
        activityController.pause().stop().destroy()
    }

    @Test fun `vertical video swipes select the starting half adjust system values and never navigate or tap`() {
        showPage()
        swipeSide(0.2f, up = true)
        compose.runOnIdle {
            assertEquals(1f, activity.window.attributes.screenBrightness, 0.001f)
            assertFalse(levels.dragging)
        }
        swipeSide(0.2f, up = false)
        compose.runOnIdle { assertEquals(0f, activity.window.attributes.screenBrightness, 0.001f) }
        swipeSide(0.8f, up = true)
        compose.runOnIdle { assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        swipeSide(0.8f, up = false)
        compose.runOnIdle {
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("0", levels.feedback?.label)
            assertEquals(0, chromeToggles)
            assertEquals(emptyList<ViewerHorizontalSwipeDirection>(), navigations)
        }
    }

    @Test fun `the centre column of video closes and opens info while the outer columns stay levels`() {
        showPage()
        swipeSide(0.5f, up = false)
        compose.runOnIdle {
            assert(dismissDrags > 0)
            assertEquals(0.5f, activity.window.attributes.screenBrightness, 0f)
            assertNull(levels.feedback)
        }
        swipeSide(0.5f, up = true)
        compose.runOnIdle { assertEquals(1, infoSwipes) }
        val pulls = dismissDrags
        swipeSide(0.2f, up = false)
        compose.runOnIdle {
            assertEquals(pulls, dismissDrags)
            assertEquals(0f, activity.window.attributes.screenBrightness, 0.001f)
        }
    }

    @Test fun `horizontal navigation stays separate and works after a vertical gesture`() {
        showPage()
        compose.onNodeWithContentDescription("Media actions").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(listOf(ViewerHorizontalSwipeDirection.Next), navigations)
            assertNull(levels.feedback)
        }
        swipeSide(0.2f, up = true)
        compose.runOnIdle { assertEquals(1, navigations.size) }
    }

    @Test fun `pinch zoom exploration and PiP cannot change levels`() {
        showPage()
        compose.onNodeWithContentDescription("Media actions").performTouchInput {
            pinch(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f),
                Offset(width * 0.1f, height * 0.3f), Offset(width * 0.9f, height * 0.7f))
        }
        compose.runOnIdle {
            assertNull(levels.feedback)
            transform.value = ViewerTransformState(zoom = 2f)
        }
        swipeSide(0.2f, up = true)
        compose.runOnIdle {
            assertNull(levels.feedback)
            transform.value = ViewerTransformState()
            layout.value = layout.value.copy(touchExploration = true)
        }
        swipeSide(0.2f, up = true)
        compose.runOnIdle {
            assertNull(levels.feedback)
            layout.value = layout.value.copy(touchExploration = false, pictureInPicture = true)
        }
        // PiP has no active media accessibility node; touch the retained underlying gesture surface.
        compose.onNodeWithContentDescription("Media actions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0.5f, activity.window.attributes.screenBrightness, 0f) }
    }

    @Test fun `still images leave brightness alone`() {
        showPage(video = false)
        swipeSide(0.2f, up = true)
        compose.runOnIdle {
            assertEquals(0.5f, activity.window.attributes.screenBrightness, 0f)
            assertNull(levels.feedback)
        }
    }

    @Test fun `level rail stays on its gesture's side during a held drag then expires after release`() {
        showPage()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Media actions").performTouchInput {
            down(Offset(width * 0.2f, height * 0.7f))
            moveTo(Offset(width * 0.2f, height * 0.4f))
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.onNodeWithContentDescription("Brightness").assertIsDisplayed()
        val indicator = compose.onNodeWithContentDescription("Brightness").fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithContentDescription("Media actions").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("rail should hug the leading edge", indicator.right < media.center.x)
        assertEquals(media.center.y, indicator.center.y, 40f)
        compose.runOnIdle { assertNotNull(levels.feedback) }
        compose.onNodeWithContentDescription("Media actions").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse(levels.dragging) }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.runOnIdle { assertNull(levels.feedback) }
        compose.onNodeWithContentDescription("Brightness").assertDoesNotExist()
    }

    @Test fun `double tap seeking does not open the controls`() {
        showPage()
        compose.onNodeWithContentDescription("Media actions").performTouchInput { doubleClick(Offset(width * 0.9f, centerY)) }
        compose.runOnIdle {
            assertEquals(1, seekInteractions)
            assertEquals(0, interactions)
            assertEquals(0, chromeToggles)
        }
    }

    private fun swipeSide(x: Float, up: Boolean) {
        compose.onNodeWithContentDescription("Media actions").performTouchInput {
            val low = Offset(width * x, height * 0.85f)
            val high = Offset(width * x, height * 0.15f)
            swipe(if (up) low else high, if (up) high else low)
        }
    }

    private fun showPage(video: Boolean = true) {
        val media = ImageRef("https://example.test/media.${if (video) "mp4" else "jpg"}", null,
            if (video) "video/mp4" else "image/jpeg")
        val post = testPost(full = media, media = listOf(media))
        val key = ViewerMediaKey(post.id, 0)
        val events = viewerRendererEvents(null, key, 0L, {}, {}, {})
        val positions = ViewerVideoPositions()
        val imageSession = ViewerImageSession()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 500.dp).viewerGestures(
                    ViewerGestureConfig(
                        enabled = !transform.value.isZoomed && !layout.value.pictureInPicture && !layout.value.touchExploration,
                        levels = levels.takeIf { video },
                    ),
                    object : ViewerGestureHandler {
                        override fun onPress() = Unit
                        override fun onHorizontalDrag(displacement: Float) = Unit
                        override fun onHorizontalRelease(displacement: Float, velocity: Float) {
                            viewerSwipeDecision(displacement, displacement, velocity, 1000f, 400f)?.let(navigations::add)
                        }
                        override fun onDismissDrag(x: Float, y: Float, height: Float) { dismissDrags++ }
                        override fun onDismissRelease(velocityY: Float) = Unit
                        override fun onInfoSwipe() { infoSwipes++ }
                        override fun onGestureEnd() = Unit
                        override fun onLevelLimit() = Unit
                    },
                )) {
                    ViewerMediaPage(
                        post, media, 0, controls.value, events, transform.value, { transform.value = it },
                        layout.value, ViewerVideoOptions(VideoQuality.AUTO, null, "Unavailable", false),
                        positions, null, imageSession, null, {}, { interactions++ }, { chromeToggles++ }, null,
                        levelControls = levels,
                        onPassiveInteraction = { seekInteractions++ },
                    )
                }
            }
        }
    }
}
