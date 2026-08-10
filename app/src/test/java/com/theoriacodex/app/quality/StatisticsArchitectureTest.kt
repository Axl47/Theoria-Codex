package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsArchitectureTest {
    private val repositoryRoot = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
        File::getParentFile,
    ).firstOrNull { root -> File(root, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `lifetime events remain attached to authoritative outcomes`() {
        val shell = source("app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt")
        val search = source("app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt")
        val forYou = source("app/src/main/java/com/theoriacodex/app/recommend/ForYouCoordinator.kt")
        val viewer = source("app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRouteWorkflow.kt")
        val actions = source("app/src/main/java/com/theoriacodex/app/ui/components/PostActionSheet.kt")

        assertTrue("App usage must observe process lifecycle", "ProcessLifecycleOwner.get().lifecycle" in shell)
        assertTrue("Search stats must follow admitted persistence", "recordAcceptedSearch(searchedSources)" in search)
        assertTrue("FYP stats must follow accepted root publication", "statisticsRepository.recordForYouSearch()" in forYou)
        assertTrue("Watched stats must share the Viewer visible-post workflow", "recordWatchedPost(" in viewer)
        assertTrue(
            "URL-copy stats must run only inside the successful clipboard branch",
            actions.indexOf("if (copyPostUrlToClipboard(context, post))") < actions.indexOf("onPostUrlCopied(post)"),
        )
        val successfulSave = shell.substringAfter("onSelectCodex = { codexId ->").substringBefore("onDismiss = {")
        assertTrue(
            "FYP saves must record only after Codex persistence returns",
            successfulSave.indexOf("codexRepository.addItem(codexId, post)") <
                successfulSave.indexOf("recordForYouSaveIfNeeded(recordForYouSave)"),
        )
        assertTrue("Codex entries must be route-owned", "recordCodexEntry(codexId)" in shell)
    }

    @Test
    fun `Settings owns one persisted Stats section and all requested groups`() {
        val state = source("app/src/main/java/com/theoriacodex/app/settings/SettingsState.kt")
        val screen = source("app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt")

        assertTrue("Stats needs an independent persisted expansion key", "STATS," in state)
        assertTrue("Settings must render the shared section card", "title = \"Stats\"" in screen)
        listOf("App Stats", "Post Stats", "Search Stats", "Tag Stats", "Codex Stats").forEach { group ->
            assertTrue("Missing Stats group $group", "StatisticsGroupTitle(\"$group\")" in screen)
        }
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
