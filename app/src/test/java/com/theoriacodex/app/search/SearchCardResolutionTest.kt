package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchCardResolutionTest {
    @get:Rule val compose = createComposeRule()

    private val resolveCalls = mutableListOf<PostId>()

    @Test
    fun `a visible iwara card asks for its details once`() {
        showCard(listPost(SourceKey.IWARA), scroll = ScrollState(0))

        compose.runOnIdle { assertEquals(listOf(PostId(SourceKey.IWARA, "p1")), resolveCalls) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, resolveCalls.size) }
    }

    @Test
    fun `an iwara card scrolled out of view spends no request`() {
        val scroll = ScrollState(0)
        showCard(listPost(SourceKey.IWARA), scroll, viewportHeight = 40.dp, spacerAbove = 200.dp)

        compose.runOnIdle { assertEquals(emptyList<PostId>(), resolveCalls) }
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, resolveCalls.size) }
    }

    @Test
    fun `cards that already carry a video or whose provider lists full media do not resolve`() {
        val hitomiVideo = listPost(SourceKey.HITOMI).copy(
            media = listOf(ImageRef("https://streaming.example.com/videos/a.mp4", null, "video/mp4")),
        )
        showCard(hitomiVideo, ScrollState(0))
        compose.runOnIdle { assertEquals(emptyList<PostId>(), resolveCalls) }
    }

    @Test
    fun `a still gallery post without video does not resolve`() {
        showCard(listPost(SourceKey.GELBOORU), ScrollState(0))
        compose.runOnIdle { assertEquals(emptyList<PostId>(), resolveCalls) }
    }

    private fun listPost(source: SourceKey): Post = testPost(
        source = source,
        sourcePostId = "p1",
        full = null,
        media = emptyList(),
    )

    private fun showCard(
        post: Post,
        scroll: ScrollState,
        viewportHeight: androidx.compose.ui.unit.Dp = 400.dp,
        spacerAbove: androidx.compose.ui.unit.Dp = 0.dp,
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 200.dp, height = viewportHeight).clipToBounds()) {
                    Column(Modifier.verticalScroll(scroll)) {
                        Box(Modifier.size(width = 200.dp, height = spacerAbove))
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = null,
                            resolvePostById = { id ->
                                resolveCalls += id
                                null
                            },
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}
