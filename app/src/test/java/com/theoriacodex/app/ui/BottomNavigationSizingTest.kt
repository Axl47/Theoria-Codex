package com.theoriacodex.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationSizingTest {
    @Test
    fun `three button navigation preserves gesture navigation control height`() {
        val gesture = calculateBottomNavigationSizing(
            windowHeightDp = 800f,
            bottomSystemInsetDp = 24f,
        )
        val threeButton = calculateBottomNavigationSizing(
            windowHeightDp = 800f,
            bottomSystemInsetDp = 48f,
        )

        assertEquals(68f, gesture.totalHeightDp, 0.001f)
        assertEquals(92f, threeButton.totalHeightDp, 0.001f)
        assertEquals(
            gesture.totalHeightDp - 24f,
            threeButton.totalHeightDp - 48f,
            0.001f,
        )
        assertEquals(gesture.iconSizeDp, threeButton.iconSizeDp)
    }

    @Test
    fun `base navbar and icon sizes remain clamped across window heights`() {
        val compact = calculateBottomNavigationSizing(
            windowHeightDp = 500f,
            bottomSystemInsetDp = 24f,
        )
        val tall = calculateBottomNavigationSizing(
            windowHeightDp = 1_200f,
            bottomSystemInsetDp = 24f,
        )

        assertEquals(68f, compact.totalHeightDp, 0.001f)
        assertEquals(25, compact.iconSizeDp)
        assertEquals(88f, tall.totalHeightDp, 0.001f)
        assertEquals(30, tall.iconSizeDp)
    }
}
