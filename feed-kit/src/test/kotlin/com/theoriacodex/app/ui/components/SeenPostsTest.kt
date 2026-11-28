package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.domain.model.PostId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SeenPostsTest {
    @get:Rule val compose = createComposeRule()
    private val posts = (0..5).map { testPost(sourcePostId = "p$it") }
    private val written = CopyOnWriteArrayList<PostId>()

    @Test fun `a card counts as seen only with at least half of it inside the viewport, at either edge (ADR 0152)`() {
        compose.setContent { Grid(eligible = posts.map { it.id }.toSet()) { written += it } }

        compose.runOnIdle { assertEquals(listOf("p1", "p2"), written.map { it.sourcePostId }.sorted()) }
    }

    @Test fun `only posts that were new when their card was seen are written (ADR 0152)`() {
        compose.setContent { Grid(eligible = setOf(posts[2].id)) { written += it } }

        compose.runOnIdle { assertEquals(listOf(posts[2].id), written.toList()) }
    }

    @Test fun `a write still running when the visit ends completes (ADR 0152)`() {
        val gate = CompletableDeferred<Unit>()
        var visiting by mutableStateOf(true)
        compose.setContent { if (visiting) Grid(eligible = posts.map { it.id }.toSet()) { gate.await(); written += it } }
        compose.waitForIdle()

        compose.runOnIdle { visiting = false }
        compose.waitForIdle()
        gate.complete(Unit)
        compose.waitUntilSettled(5_000) { written.size == 2 }
    }

    @Composable
    private fun Grid(eligible: Set<PostId>, onSeen: suspend (Set<PostId>) -> Unit) {
        val offset = with(LocalDensity.current) { 70.dp.roundToPx() }
        val grid = rememberLazyStaggeredGridState(initialFirstVisibleItemScrollOffset = offset)
        SeenPostsEffect(grid, posts, eligible, onSeen)
        LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(1), Modifier.height(260.dp), state = grid) {
            items(posts, key = { feedPostItemKey(it.id) }) { Box(Modifier.height(100.dp)) }
        }
    }
}
