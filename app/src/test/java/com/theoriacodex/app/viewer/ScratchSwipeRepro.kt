package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScratchSwipeRepro {
    @get:Rule val compose = createComposeRule()

    private fun posts(n: Int): List<Post> {
        val image = ImageRef(url = null, localPath = "/missing/swipe.jpg", mime = "image/jpeg")
        val base = Post(
            id = PostId(SourceKey.GELBOORU, "p0"), preview = image, full = image, media = listOf(image),
            pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
        return (0 until n).map { base.copy(id = PostId(SourceKey.GELBOORU, "p$it")) }
    }

    private fun run(label: String, resolve: Boolean, gap: Long, gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        val ps = posts(8)
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("s"), ps, requiresResolution = { resolve }))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value, creatorBrowsingSources = emptySet(),
                    onAction = { state.value = reduceViewerState(state.value, it).state },
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1000)
        val seq = mutableListOf<Int>()
        repeat(6) {
            compose.onRoot().performTouchInput(gesture)
            compose.mainClock.advanceTimeBy(gap)
            seq += state.value.currentPageIndex
        }
        compose.mainClock.advanceTimeBy(1000)
        println("REPRO $label gap=$gap -> $seq")
    }

    private val slow: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
        down(Offset(width * 0.8f, height * 0.5f))
        for (i in 1..10) { advanceEventTime(30); moveTo(Offset(width * (0.8f - 0.04f * i), height * 0.5f)) }
        advanceEventTime(200); up()
    }
    private val fast: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
        down(Offset(width * 0.8f, height * 0.5f))
        for (i in 1..5) { advanceEventTime(10); moveTo(Offset(width * (0.8f - 0.05f * i), height * 0.5f)) }
        up()
    }

    @Test fun slowLong() = run("slow", false, 1500, slow)
    @Test fun slowShortGap() = run("slow", false, 350, slow)
    @Test fun fastLong() = run("fast", false, 1500, fast)
    @Test fun fastShortGap() = run("fast", false, 250, fast)
    @Test fun fastResolve() = run("fastResolve", true, 1500, fast)
}
