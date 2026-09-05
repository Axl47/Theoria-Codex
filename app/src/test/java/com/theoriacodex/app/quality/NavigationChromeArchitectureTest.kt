package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationChromeArchitectureTest {
    private val repositoryRoot = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
        File::getParentFile,
    ).firstOrNull { root -> File(root, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `secondary routes use the shared back title and action frame`() {
        listOf(
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
            "app/src/main/java/com/theoriacodex/app/viewer/ViewerChrome.kt",
        ).forEach { path ->
            assertTrue("$path must use shared secondary chrome", "SecondaryScreenAppBar(" in source(path))
        }
        assertTrue(
            "ViewerScreen must delegate its chrome to ViewerChrome",
            "ViewerChrome(" in source("app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt"),
        )
    }

    @Test
    fun `feed routes share filter components`() {
        listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
        ).forEach { path ->
            val route = source(path)
            assertTrue("$path must use the shared filter sheet", "FeedFilterSheet(" in route)
            assertTrue("$path must use the shared filter affordance", "FeedFilterFab(" in route)
        }
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
