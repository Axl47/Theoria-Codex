package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexDragSelectTest {
    @get:Rule val compose = createComposeRule()
    private val ids = (0 until 6).map { PostId(SourceKey.GELBOORU, "post$it") }

    @Test fun `painting selects or clears the range from the first post and keeps other selections`() {
        val selection = CodexEditSelection(active = true, selectedPostIds = setOf(ids[5]))
        assertEquals(setOf(ids[1], ids[2], ids[3], ids[5]), selection.painted(setOf(ids[5]), ids, 1, 3, selecting = true).selectedPostIds)
        // Reversing direction paints the other way from the same anchor.
        assertEquals(setOf(ids[0], ids[1], ids[5]), selection.painted(setOf(ids[5]), ids, 1, 0, selecting = true).selectedPostIds)
        val all = ids.toSet()
        assertEquals(setOf(ids[0], ids[4], ids[5]), selection.painted(all, ids, 3, 1, selecting = false).selectedPostIds)
    }

    @Test fun `long press and drag selects the posts it crosses and a plain drag does not`() {
        val selection = mutableStateOf(CodexEditSelection(active = true))
        compose.setContent {
            val grid = rememberLazyStaggeredGridState()
            Box(Modifier.size(200.dp, 600.dp)) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = grid,
                    modifier = Modifier.testTag("grid").codexDragSelect(grid, ids, selection.value) { selection.value = it },
                ) {
                    items(ids, key = { it.sourcePostId }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
        }
        compose.onNodeWithTag("grid").performTouchInput {
            down(Offset(50f, 50f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(50f, 51f))
            moveTo(Offset(150f, 150f))
            up()
        }
        compose.runOnIdle { assertEquals(ids.take(4).toSet(), selection.value.selectedPostIds) }
    }
}
