package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundarySourceTest {
    private val userDirectory = requireNotNull(System.getProperty("user.dir")) {
        "The JVM did not expose user.dir"
    }
    private val repositoryRoot: File = generateSequence(File(userDirectory).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from $userDirectory")

    @Test
    fun `production UI collects observable state with lifecycle awareness`() {
        val violations = appProductionSources()
            .filter { source -> Regex("""\bcollectAsState\s*\(""").containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Use collectAsStateWithLifecycle in production UI",
            violations = violations,
        )
    }

    @Test
    fun `coordinators engines and orchestrators stay independent from Compose state`() {
        val ownerName = Regex("""(?:Coordinator|Engine|Orchestrator)\.kt$""")
        val forbiddenImport = Regex(
            """^import androidx\.compose\.runtime\.mutable(?:State|IntState|LongState|FloatState|DoubleState)""",
            setOf(RegexOption.MULTILINE),
        )
        val violations = productionSources()
            .filter { source -> ownerName.containsMatchIn(source.name) }
            .filter { source -> forbiddenImport.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Coordinator, engine, and orchestrator state must remain platform independent",
            violations = violations,
        )
    }

    @Test
    fun `file backed repositories are constructed only by the application container`() {
        val container = "app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt"
        val fileBackedConstruction = Regex("""\bFileBacked[A-Za-z0-9_]*Repository\s*\(""")
        val violations = appProductionSources()
            .filter { source -> relativePath(source) != container }
            .filter { source -> fileBackedConstruction.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Durable repository construction belongs to TheoriaAppContainer",
            violations = violations,
        )
        assertTrue(
            "The durable-construction guard became vacuous because the application container owns no file-backed repository",
            fileBackedConstruction.containsMatchIn(File(repositoryRoot, container).readText()),
        )
    }

    @Test
    fun `production Recents ownership is Room only and startup gated`() {
        val container = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt",
        ).readText()
        val production = productionSources().joinToString("\n") { it.readText() }

        assertTrue("The app container must construct Room Recents", "RoomRecentsRepository(contentDatabase)" in container)
        assertTrue("The app container must construct the one-time Recents importer", "RoomRecentsLegacyImporter(" in container)
        assertTrue("Recents import must finish inside the durable startup gate", "recentsImporter.importAndArchive" in container)
        assertTrue("The removed whole-file Recents repository must not return", "class FileBackedRecentsRepository" !in production)
    }

    @Test
    fun `app shell does not collect destination owned state`() {
        val appPath = "app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt"
        val boundariesPath =
            "app/src/main/java/com/theoriacodex/app/ui/routes/DestinationStateBoundaries.kt"
        val app = File(repositoryRoot, appPath).readText()
        val shell = app.substringAfter("internal fun TheoriaAppContent(")
            .substringBefore("private fun StartupUpdatePromptCard(")
        val boundaries = File(repositoryRoot, boundariesPath).readText()
        val forbiddenShellReads = listOf(
            "observeSettings()",
            "observeWatchedPosts()",
            "observeSearches()",
            "observeActivity()",
            "observeLikedPostIds(",
            "observeLikes(",
            "observeCodices()",
            "observeCodexItems(",
            ".recoveryState.collectAsStateWithLifecycle",
            "settingsOwner.state.collectAsStateWithLifecycle",
            "searchRouteOwner?.state",
            "forYouRouteOwner?.state",
            "creatorRouteOwner?.state",
        )
        val violations = forbiddenShellReads.filter(shell::contains)

        assertNoViolations(
            rule = "TheoriaAppContent may collect only shell-global observable state",
            violations = violations,
        )
        assertTrue(
            "The shell-global AppShell owner must remain lifecycle aware",
            "appShellOwner.state.collectAsStateWithLifecycle()" in shell,
        )
        listOf(
            "BrowsingDestinationStateBoundary",
            "RecentsDestinationStateBoundary",
            "CodexDestinationStateBoundary",
            "SettingsDestinationStateBoundary",
            "CredentialRecoveryOverlay",
            "ViewerDestinationStateBoundary",
        ).forEach { boundary ->
            assertTrue("$boundary must be used by the shell", "$boundary(" in shell)
            assertTrue("$boundary must be owned by the destination boundary file", "fun $boundary(" in boundaries)
        }
        listOf(
            "owner.state.collectAsStateWithLifecycle()" to "Settings state",
            "observeWatchedPosts()" to "Recents watched state",
            "observeSearches()" to "Recents search state",
            "observeActivity()" to "Recents merged activity",
            "observeCodices()" to "Codex list state",
            "observeCodexItems(" to "Codex item state",
            "recoveryState.collectAsStateWithLifecycle()" to "credential recovery state",
            "searchOwner?.state" to "retained Search Viewer state",
            "forYouOwner?.state" to "retained For You Viewer state",
            "creatorOwner?.state" to "retained Creator Viewer state",
        ).forEach { (read, owner) ->
            assertTrue("$owner must be read below the shell boundary", read in boundaries)
        }
        assertTrue(
            "Retained Search, For You, and Creator handles must stay available to Viewer",
            listOf("searchOwner = searchRouteOwner", "forYouOwner = forYouRouteOwner", "creatorOwner = creatorRouteOwner")
                .all(shell::contains),
        )
        assertTrue(
            "Leaf destination boundaries must receive narrow dependency groups, not the app container",
            "appContainer: TheoriaAppContainer" !in boundaries,
        )
    }

    @Test
    fun `feed composables delegate animated duration enrichment to route owners`() {
        val screens = listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
        )
        val forbiddenFragments = listOf(
            "probeRemoteVideoDurationMs(",
            "durationResolutionRequests",
            "animatedDurationResolutionCandidates(",
            "ANIMATED_DURATION_RESOLVE_BATCH_SIZE",
        )
        val violations = screens.flatMap { path ->
            val text = File(repositoryRoot, path).readText()
            forbiddenFragments.filter { fragment -> fragment in text }
                .map { fragment -> "$path contains $fragment" }
        }

        assertNoViolations(
            rule = "Feed composables must emit typed enrichment actions instead of owning resolve/probe loops",
            violations = violations,
        )
        screens.forEach { path ->
            assertTrue(
                "$path must retain its typed duration-enrichment action",
                "RequestAnimatedDurationEnrichment" in File(repositoryRoot, path).readText(),
            )
        }
        listOf(
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
        ).forEach { path ->
            val text = File(repositoryRoot, path).readText()
            assertTrue("$path must not accept a duration resolver", "resolvePost: suspend" !in text)
            assertTrue("$path must not accept resolved-post mutation", "rememberResolvedPost:" !in text)
        }

        val servicePath = "app/src/main/java/com/theoriacodex/app/media/AnimatedDurationEnrichmentService.kt"
        val containerPath = "app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt"
        val serviceConstruction = Regex("""\bAnimatedDurationEnrichmentService\s*\(""")
        val constructionViolations = appProductionSources()
            .filter { source -> relativePath(source) !in setOf(servicePath, containerPath) }
            .filter { source -> serviceConstruction.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()
        assertNoViolations(
            rule = "The application container must be the sole enrichment-service owner",
            violations = constructionViolations,
        )
        assertTrue(
            "The application-owned service guard became vacuous",
            serviceConstruction.containsMatchIn(File(repositoryRoot, containerPath).readText()),
        )
    }

    private fun appProductionSources(): Sequence<File> = kotlinSourcesUnder("app/src/main")

    private fun productionSources(): Sequence<File> {
        return repositoryRoot.listFiles().orEmpty()
            .asSequence()
            .filter { directory ->
                directory.isDirectory &&
                    (directory.name == "app" || directory.name == "app-logic" || directory.name.startsWith("core-"))
            }
            .flatMap { module ->
                File(module, "src/main").walkTopDown()
                    .filter(File::isFile)
                    .filter { it.extension == "kt" }
            }
    }

    private fun kotlinSourcesUnder(relativeDirectory: String): Sequence<File> {
        return File(repositoryRoot, relativeDirectory)
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" }
    }

    private fun relativePath(file: File): String = file.relativeTo(repositoryRoot).invariantSeparatorsPath

    private fun assertNoViolations(rule: String, violations: List<String>) {
        assertTrue(
            buildString {
                append(rule)
                if (violations.isNotEmpty()) {
                    append(":\n")
                    append(violations.joinToString(separator = "\n"))
                }
            },
            violations.isEmpty(),
        )
    }
}
