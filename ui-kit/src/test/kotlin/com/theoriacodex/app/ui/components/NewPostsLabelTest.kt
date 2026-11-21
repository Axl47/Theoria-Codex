package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NewPostsLabelTest {
    @Test fun `counts show until the cap`() {
        assertEquals(listOf(null, "1 new", "20 new", "20+ new"), listOf(0, 1, 20, 21).map(::newPostsLabel))
    }
}
