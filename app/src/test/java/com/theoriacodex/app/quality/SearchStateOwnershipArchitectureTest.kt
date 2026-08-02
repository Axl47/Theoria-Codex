package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStateOwnershipArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `search view model is the only mutable route state owner`() {
        val coordinator = file("app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt").readText()
        val viewModel = file("app/src/main/java/com/theoriacodex/app/search/SearchViewModel.kt").readText()
        val contract = file(
            "app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchExecutionContract.kt",
        ).readText()

        assertTrue("SearchViewModel must own the observable state", "MutableStateFlow" in viewModel)
        assertTrue("Coordinator must return immutable root results", "SearchExecutionResult" in coordinator)
        assertTrue("Coordinator must return immutable page results", "SearchPageResult" in coordinator)
        assertTrue("Execution contracts must bind every result to an execution key", "val executionKey: String" in contract)
        listOf(
            "MutableStateFlow",
            "StateFlow<",
            "SearchCoordinatorSnapshot",
            "var draftQuery",
            "var appliedQuery",
            "var results",
            "var statuses",
            "var loading",
            "activeRootSearch",
        ).forEach { competingOwner ->
            assertFalse("SearchCoordinator must not retain route ownership: $competingOwner", competingOwner in coordinator)
        }
        assertFalse(
            "The removed snapshot bridge must not return",
            file("app/src/main/java/com/theoriacodex/app/search/state/SearchCoordinatorStateMapper.kt").exists(),
        )
    }

    @Test
    fun `search composable dispatches typed actions instead of executing route jobs`() {
        val route = file("app/src/main/java/com/theoriacodex/app/ui/routes/SearchRoute.kt").readText()
        val screen = file("app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt").readText()

        assertTrue("Route must dispatch typed SearchAction values", "SearchAction." in route)
        listOf("executeInitial(", "executePage(", "persistAppliedSearch(", "MutableStateFlow").forEach { forbidden ->
            assertFalse("Search UI must not own execution: $forbidden", forbidden in screen)
        }
    }

    private fun file(path: String): File = File(repositoryRoot, path)
}
