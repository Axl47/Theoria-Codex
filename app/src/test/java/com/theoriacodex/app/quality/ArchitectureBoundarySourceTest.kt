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
            .substringBefore("private fun openInBrowser(")
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
    fun `top level destination navigation never animates through intermediate tabs`() {
        val appPath = "app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt"
        val app = File(repositoryRoot, appPath).readText()
        val shell = app.substringAfter("internal fun TheoriaAppContent(")
            .substringBefore("private fun StartupUpdatePromptCard(")

        assertTrue(
            "Top-level navigation must jump directly so intermediate feeds are not activated",
            "animateScrollToPage(" !in shell,
        )
        assertTrue(
            "The top-level pager must retain direct page navigation",
            "topLevelPagerState.scrollToPage(" in shell,
        )
    }

    @Test
    fun `FYP Recents replay targets For You and Searches precedes FYP`() {
        val app = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt",
        ).readText()
        val recents = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/recents/RecentsScreen.kt",
        ).readText()
        val filterOrder = recents.substringAfter("private enum class RecentsFilter")
            .substringBefore("private fun activityKey")

        assertTrue("FYP rows must dispatch their exact historical seed", "entry.fypSeedBySource()" in app)
        assertTrue("FYP replay must use the navigation-owned route action", "ForYouAction.ReplaySearch(" in app)
        assertTrue("FYP replay must target the For You tab", "homeTabRoute = TopLevelDestination.ForYou.route" in app)
        assertTrue(
            "Searches must appear before FYP in Recents",
            filterOrder.indexOf("SEARCHES(") < filterOrder.indexOf("FYP("),
        )
    }

    @Test
    fun `feed composables delegate animated duration enrichment to route owners`() {
        val screens = listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
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
            val text = File(repositoryRoot, path).readText()
            assertTrue(
                "$path must read separate duration state and emit typed route events",
                "durationStates" in text &&
                    "onDurationFilterChanged" in text &&
                    "onDurationPostVisibilityChanged" in text,
            )
            assertTrue(
                "$path must not retain the result-list enrichment trigger",
                "RequestAnimatedDurationEnrichment" !in text &&
                    "shouldRequestAnimatedDurationEnrichment" !in text,
            )
        }
        listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
        ).forEach { path ->
            val text = File(repositoryRoot, path).readText()
            assertTrue(
                "$path must restart pagination when pending duration decisions settle",
                "durationReadiness.pendingCount" in text,
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

        val containerPath = "app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt"
        assertTrue(
            "The transitional duration lanes and service must be removed",
            !File(
                repositoryRoot,
                "app/src/main/java/com/theoriacodex/app/media/AnimatedDurationEnrichmentService.kt",
            ).exists() &&
                !File(
                    repositoryRoot,
                    "app/src/main/java/com/theoriacodex/app/search/" +
                        "SearchAnimatedDurationEnrichmentOwner.kt",
                ).exists() &&
                !File(
                    repositoryRoot,
                    "app/src/main/java/com/theoriacodex/app/codex/CodexDetailDurationViewModel.kt",
                ).exists(),
        )
        val coordinatorConstruction = Regex("""\bMediaDurationCoordinator\s*\(""")
        val coordinatorPath = "app/src/main/java/com/theoriacodex/app/media/MediaDurationCoordinator.kt"
        val coordinatorConstructionViolations = appProductionSources()
            .filter { source -> relativePath(source) !in setOf(containerPath, coordinatorPath) }
            .filter { source -> coordinatorConstruction.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()
        assertNoViolations(
            rule = "The application container must be the sole duration-coordinator owner",
            violations = coordinatorConstructionViolations,
        )
        val container = File(repositoryRoot, containerPath).readText()
        assertTrue(
            "The application-owned duration coordinator guard became vacuous",
            coordinatorConstruction.containsMatchIn(container) &&
                "val mediaDurationCoordinator: MediaDurationCoordinator" in container,
        )
        val coordinator = File(
            repositoryRoot,
            coordinatorPath,
        ).readText()
        assertTrue(
            "Duration publication must stay separate from demand submission",
            "private fun publishKnownLocked" in coordinator &&
                "metadata publication never becomes a new demand input" in coordinator,
        )
        val routeOwner = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/media/MediaDurationRouteViewModel.kt",
        ).readText()
        assertTrue(
            "Route demand must reconcile deltas instead of rescanning work through feed state",
            "reconcileDemandLane" in routeOwner &&
                "desired - current" in routeOwner &&
                "current - desired" in routeOwner,
        )
        assertTrue(
            "Viewport events must reconcile one post and reuse an unchanged feed snapshot",
            "synchronizeVisibleDemand(post.id)" in routeOwner &&
                "requestedPostsReference === posts" in routeOwner,
        )
        assertTrue(
            "Feed snapshots must be built away from the UI dispatcher",
            "withContext(snapshotDispatcher)" in routeOwner,
        )
        assertTrue(
            "Routine badges must observe one key while route maps stay filter-only",
            "fun observeState(post: Post)" in routeOwner &&
                "if (filterActive) demandLock.withLock" in routeOwner &&
                "if (!filterActive) return" in routeOwner,
        )
        assertTrue(
            "Coordinator bookkeeping must run on its application-owned context",
            "coordinationContext" in coordinator &&
                "withContext(coordinationContext)" in coordinator,
        )
        val viewer = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt",
        ).readText()
        assertTrue(
            "Viewer duration publication must use one-shot player callbacks",
            "onDurationKnown: (Long) -> Unit" in viewer &&
                "onAuthoritativeDurationKnown(post, durationMs)" in viewer,
        )
        listOf(
            "TheoriaDurationDemand",
            "TheoriaDurationResolve",
            "TheoriaDurationProbe",
            "TheoriaDurationPublish",
            "TheoriaDurationSettled",
            "TheoriaDurationWorkload",
        ).forEach { traceName ->
            assertTrue("The duration coordinator must retain $traceName", traceName in coordinator)
        }
        val durationEntity = File(
            repositoryRoot,
            "core-data-android/src/main/java/com/theoriacodex/data/android/room/" +
                "MediaDurationEntity.java",
        ).readText()
        val roomDatabase = File(
            repositoryRoot,
            "core-data-android/src/main/java/com/theoriacodex/data/android/room/" +
                "TheoriaRoomDatabase.java",
        ).readText()
        assertTrue(
            "Duration persistence must remain independent, bounded metadata without request data",
            "tableName = \"media_durations\"" in durationEntity &&
                "url" !in durationEntity.lowercase() &&
                "header" !in durationEntity.lowercase() &&
                "version = 5" in roomDatabase &&
                "MIGRATION_4_5" in roomDatabase &&
                "DEFAULT_MEDIA_DURATION_ENTRY_LIMIT = 4_096" in File(
                    repositoryRoot,
                    "core-data/src/main/kotlin/com/theoriacodex/data/repository/" +
                        "MediaDurationRepository.kt",
                ).readText(),
        )
        assertTrue(
            "The application coordinator must consult and persist the independent duration store",
            "durationRepository: MediaDurationRepository?" in coordinator &&
                "loadStoredState(demand.key)" in coordinator &&
                "persistState(work.key, state)" in coordinator,
        )
        val acquisitionEngine = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/media/MediaDurationAcquisitionEngine.kt",
        ).readText()
        val boundedProbe = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/media/BoundedMediaDurationProbe.kt",
        ).readText()
        val sourceAdapter = File(
            repositoryRoot,
            "core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt",
        ).readText()
        assertTrue(
            "Duration metadata must be an optional narrow source capability",
            "interface DurationMetadataSourceAdapter" in sourceAdapter &&
                "resolveDurationMetadata(post: Post)" in sourceAdapter,
        )
        assertTrue(
            "Duration acquisition must not hydrate complete posts through the generic source API",
            "resolvePost(" !in acquisitionEngine &&
                "DurationMetadataSourceAdapter" in acquisitionEngine,
        )
        assertTrue(
            "Duration acquisition must retain one worker and a 12-second timeout",
            "DEFAULT_MAX_CONCURRENT_WORK = 1" in coordinator &&
                "DEFAULT_DURATION_ACQUISITION_TIMEOUT_MS = 12_000L" in acquisitionEngine &&
                "withTimeoutOrNull(operationTimeoutMs)" in acquisitionEngine,
        )
        assertTrue(
            "Remote duration parsing must retain strict byte and time budgets",
            "DEFAULT_DURATION_PROBE_WINDOW_BYTES = 256 * 1024" in boundedProbe &&
                "DEFAULT_DURATION_PROBE_TIMEOUT_MS = 12_000L" in boundedProbe &&
                "SourceByteRange" in boundedProbe &&
                "maxBodyBytes = byteWindowLimit" in boundedProbe &&
                "requestHeaders()" in boundedProbe,
        )
        val productionMediaSources = kotlinSourcesUnder(
            "app/src/main/java/com/theoriacodex/app/media",
        ).joinToString("\n") { source -> source.readText() }
        assertTrue(
            "Production duration acquisition must not use MediaMetadataRetriever",
            "MediaMetadataRetriever" !in productionMediaSources,
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
