package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenOwnershipTest {
    @Test
    fun `viewer exposes one immutable route state entry point`() {
        val entryPoints = Class.forName("com.theoriacodex.app.viewer.ViewerScreenKt")
            .declaredMethods
            .filter { method -> method.name == "ViewerScreen" }

        assertEquals(1, entryPoints.size)
        val parameterTypes = entryPoints.single().parameterTypes.toSet()
        assertTrue(ViewerUiState::class.java in parameterTypes)
        assertFalse(List::class.java in parameterTypes)
    }
}
