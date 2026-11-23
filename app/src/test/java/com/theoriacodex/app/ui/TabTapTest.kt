package com.theoriacodex.app.ui

import com.theoriacodex.app.ui.TopLevelDestination.ForYou
import com.theoriacodex.app.ui.TopLevelDestination.Library
import com.theoriacodex.app.ui.TopLevelDestination.Search
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pages: Search 0, For You 1, Library 2. */
class TabTapTest {
    @Test fun `a tap on the resting tab reselects it, and on another tab moves`() {
        assertEquals(TabTap.Reselect, tabTap(Library, targetPage = 2, settledPage = 2, scrolling = false))
        assertEquals(TabTap.Move, tabTap(ForYou, targetPage = 2, settledPage = 2, scrolling = false))
    }

    @Test fun `during a scroll from Search to Library`() {
        assertEquals(TabTap.LetFinish, tabTap(Library, targetPage = 2, settledPage = 0, scrolling = true))
        assertEquals(TabTap.Move, tabTap(ForYou, targetPage = 2, settledPage = 0, scrolling = true))
        assertEquals(TabTap.Move, tabTap(Search, targetPage = 2, settledPage = 0, scrolling = true))
    }
}
