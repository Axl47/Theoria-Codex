package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
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
            "app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt",
        ).forEach { path ->
            assertTrue("$path must use shared secondary chrome", "SecondaryScreenAppBar(" in source(path))
        }
    }

    @Test
    fun `feed routes share transient filter chrome and active fab state`() {
        listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
        ).forEach { path ->
            val route = source(path)
            assertTrue("$path must use the shared filter sheet", "FeedFilterSheet(" in route)
            assertTrue("$path must use the shared filter affordance", "FeedFilterFab(" in route)
            assertTrue("$path must provide route-owned active state", "active =" in route)
            assertFalse("$path must not add a persistent summary row", "Active filters:" in route)
        }
    }

    @Test
    fun `codex sorting lives in the filter sheet and viewer receives the visible collection`() {
        val route = source("app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt")
        val header = route.substring(
            route.indexOf("private fun CodexDetailHeader("),
            route.indexOf("private fun CodexDetailHeaderActions("),
        )

        assertFalse("Codex header must not retain the old sort row", "CodexSortMode.entries" in header)
        assertTrue("Codex sheet must own sort options", "items(CodexSortMode.entries" in route)
        assertTrue("Codex Viewer launch must carry the visible list", "onOpenViewer(visiblePosts, index)" in route)
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
