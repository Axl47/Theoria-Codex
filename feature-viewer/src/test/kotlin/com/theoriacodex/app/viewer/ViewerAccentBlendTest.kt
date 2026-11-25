package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerAccentBlendTest {
    @get:Rule val compose = createComposeRule()

    private val red = Color(0xFFE85A5A)
    private val blue = Color(0xFF5A7DE8)
    private val first = testPost(sourcePostId = "first")
    private val second = testPost(sourcePostId = "second")
    private lateinit var accent: ViewerAccentState
    private var probeCompositions = 0
    private lateinit var pager: PagerState

    @Test fun `halfway through a swipe between known tints the accent is their blend (ADR 0166)`() {
        show { post, _ -> if (post == first) red else blue }

        swipeBy(0.5f)

        assertEquals(lerp(red, blue, 0.5f), accent())
        swipeBy(-0.5f)
        assertEquals(red, accent())
    }

    @Test fun `an unknown next tint keeps the accent until it arrives, then fades in (ADR 0166)`() {
        val arriving = CompletableDeferred<Color>()
        show { post, cachedOnly -> if (post == first) red else if (cachedOnly) arriving.await() else blue }

        swipeBy(0.5f)
        assertEquals(red, accent())

        compose.mainClock.autoAdvance = false
        arriving.complete(blue)
        compose.mainClock.advanceTimeBy(TheoriaMotion.SHORT_MS / 2L)
        compose.waitForIdle()
        assertNotEquals(red, accent())
        assertNotEquals(lerp(red, blue, 0.5f), accent())
        compose.mainClock.advanceTimeBy(TheoriaMotion.SHORT_MS.toLong())
        compose.waitForIdle()
        assertEquals(lerp(red, blue, 0.5f), accent())
    }

    @Test fun `swiping changes the accent without recomposing its readers (ADR 0166)`() {
        show { post, _ -> if (post == first) red else blue }
        val settled = probeCompositions

        listOf(0.1f, 0.2f, 0.3f, -0.4f).forEach(::swipeBy)

        assertClose(lerp(red, blue, 0.2f), accent())
        assertEquals(settled, probeCompositions)
    }

    private fun show(tint: suspend (Post, Boolean) -> Color?) {
        val posts = listOf(first, second)
        compose.setContent {
            pager = rememberPagerState { posts.size }
            accent = rememberViewerAccent(first, 0, posts, pager, enabled = true, sampler = ViewerAccentSampler { post, cachedOnly -> tint(post, cachedOnly) })
            Column {
                HorizontalPager(pager, Modifier.size(200.dp)) { Box(Modifier.fillMaxSize()) }
                CompositionLocalProvider(LocalViewerAccent provides accent) { AccentProbe() }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun AccentProbe() {
        val accent = LocalViewerAccent.current
        SideEffect { probeCompositions++ }
        Box(Modifier.size(8.dp).drawBehind { drawRect(accent()) })
    }

    private fun assertClose(expected: Color, actual: Color) {
        listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue).forEach { (want, got) ->
            assertEquals(want, got, 0.01f)
        }
    }

    private fun swipeBy(pages: Float) {
        compose.runOnIdle { pager.dispatchRawDelta(pages * pager.layoutInfo.pageSize) }
        compose.waitForIdle()
    }
}
