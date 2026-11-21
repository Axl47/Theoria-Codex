package com.theoriacodex.app.ui.undo

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UndoSurfacesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `only the surface the offer is anchored to draws it`() {
        val slot = UndoSlot()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var acted = false
        try {
            compose.setContent {
                ProvideUndoSlot(slot) {
                    Column {
                        UndoPill(UndoAnchor("codex-detail", "a"))
                        UndoPill(UndoAnchor("codex-detail", "b"))
                        UndoRow(UndoAnchor("history", "a"))
                    }
                }
            }
            compose.runOnIdle {
                slot.offer(scope, UndoOffer("2 posts removed", UndoAnchor("codex-detail", "a"), lifetime = UndoLifetime.UntilLeft) { acted = true })
            }
            compose.onAllNodesWithText("2 posts removed").assertCountEquals(1)
            compose.onNodeWithText("Undo").performClick()
            compose.onAllNodesWithText("2 posts removed").assertCountEquals(0)
            assertTrue(acted)
        } finally {
            scope.cancel()
        }
    }
}
