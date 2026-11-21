package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.lift.LiftAction
import com.theoriacodex.app.lift.LiftPhase
import com.theoriacodex.app.lift.LiftSession
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.navigation.LiftController
import com.theoriacodex.app.ui.navigation.LiftLike
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchCardLiftTest {
    @get:Rule val compose = createComposeRule()

    private val post = testPost()
    private val session = LiftSession(touchSlopPx = 8f)
    private var menus = 0
    private var clicks = 0

    @Test
    fun `a long-press released in place opens the post menu, with or without a lift`() {
        showCard(session)
        compose.onNodeWithTag(searchCardTestTag(post.id)).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(Triple(1, 0, LiftPhase.Idle), Triple(menus, clicks, session.phase)) }
    }

    @Test
    fun `a card outside the shell keeps its long-press menu`() {
        showCard(LiftController.None)
        compose.onNodeWithTag(searchCardTestTag(post.id)).performTouchInput { longClick() }

        compose.runOnIdle { assertEquals(1 to 0, menus to clicks) }
    }

    @Test
    fun `dropping on a target acts without opening the menu or the post`() {
        session.targetAt = { LiftAction.NewCodex }
        showCard(session)
        compose.onNodeWithTag(searchCardTestTag(post.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(60f, 0f))
            up()
        }

        compose.runOnIdle {
            assertTrue(session.phase is LiftPhase.Settling)
            assertEquals(0 to 0, menus to clicks)
        }
    }

    @Test
    fun `a card in a search grid lifts with the grid's applied query`() {
        val applied = Query.newest(SourceKey.PIXIV)
        var started: Query? = null
        val controller = object : LiftController by LiftController.None {
            override fun start(post: Post, cardBounds: Rect, pointer: Offset, query: Query?, like: LiftLike?): Boolean {
                started = query
                return false
            }
        }
        showCard(controller, query = applied)
        compose.onNodeWithTag(searchCardTestTag(post.id)).performTouchInput { longClick() }

        compose.runOnIdle { assertEquals(applied, started) }
    }

    @Test
    fun `accessibility services keep a long-click action that opens the menu`() {
        showCard(session)
        val card = compose.onNodeWithTag(searchCardTestTag(post.id))
        card.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        card.performSemanticsAction(SemanticsActions.OnLongClick)

        compose.runOnIdle { assertEquals(1, menus) }
    }

    private fun showCard(controller: LiftController, query: Query? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalLiftController provides controller, LocalLiftQuery provides query) {
                MaterialTheme {
                    Box(Modifier.size(200.dp)) {
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = null,
                            onClick = { clicks++ },
                            onLongPress = { menus++ },
                        )
                    }
                }
            }
        }
    }
}
