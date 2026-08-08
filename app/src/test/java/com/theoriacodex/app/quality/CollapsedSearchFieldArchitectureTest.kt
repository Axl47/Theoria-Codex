package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsedSearchFieldArchitectureTest {
    private val repositoryRoot = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
        File::getParentFile,
    ).firstOrNull { root -> File(root, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `collapsed context is presentation only and reads authoritative applied state`() {
        val screen = source("app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt")
        val formatter = source(
            "app/src/main/java/com/theoriacodex/app/search/CollapsedSearchContext.kt",
        )

        assertTrue("Search must pass its applied query", "appliedQuery = state.query.applied" in screen)
        assertTrue(
            "Search must pass its applied source scope",
            "appliedSourceScope = state.query.appliedSourceScope" in screen,
        )
        assertTrue("Summary must remain in the existing placeholder", "collapsedSearchContext ?:" in screen)
        assertTrue("Focusing must remove placeholder presentation", "placeholder = if (!searchFieldFocused)" in screen)
        assertFalse("Formatter must not depend on draft state", ".draft" in formatter)
        assertFalse("The feature must not add a persistent summary row", "Applied search:" in screen)
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
