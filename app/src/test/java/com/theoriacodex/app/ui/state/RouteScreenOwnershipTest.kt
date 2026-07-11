package com.theoriacodex.app.ui.state

import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.viewer.state.ViewerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteScreenOwnershipTest {
    @Test
    fun `major route screens expose one immutable state entry point without coordinators`() {
        assertSingleStateScreen(
            className = "com.theoriacodex.app.search.SearchScreenKt",
            methodName = "SearchScreen",
            stateType = SearchUiState::class.java,
            forbiddenType = SearchCoordinator::class.java,
        )
        assertSingleStateScreen(
            className = "com.theoriacodex.app.recommend.ForYouScreenKt",
            methodName = "ForYouScreen",
            stateType = ForYouUiState::class.java,
            forbiddenType = ForYouCoordinator::class.java,
        )
        assertSingleStateScreen(
            className = "com.theoriacodex.app.creator.CreatorProfileScreenKt",
            methodName = "CreatorProfileScreen",
            stateType = CreatorUiState::class.java,
            forbiddenType = CreatorProfileCoordinator::class.java,
        )
        assertSingleStateScreen(
            className = "com.theoriacodex.app.viewer.ViewerScreenKt",
            methodName = "ViewerScreen",
            stateType = ViewerUiState::class.java,
            forbiddenType = List::class.java,
        )
    }

    private fun assertSingleStateScreen(
        className: String,
        methodName: String,
        stateType: Class<*>,
        forbiddenType: Class<*>,
    ) {
        val entryPoints = Class.forName(className)
            .declaredMethods
            .filter { method -> method.name == methodName }

        assertEquals("$methodName must have one rendering boundary", 1, entryPoints.size)
        val parameters = entryPoints.single().parameterTypes.toSet()
        assertTrue("$methodName must accept immutable route state", stateType in parameters)
        assertFalse("$methodName must not accept $forbiddenType", forbiddenType in parameters)
    }
}
