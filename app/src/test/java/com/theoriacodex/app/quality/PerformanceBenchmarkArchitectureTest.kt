package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceBenchmarkArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `deterministic fixture is benchmark-release only and storage independent`() {
        val fixture = file(
            "app/src/benchmarkRelease/java/com/theoriacodex/app/benchmark/BenchmarkFixtureActivity.kt",
        )
        val appManifest = file("app/src/main/AndroidManifest.xml").readText()
        val benchmarkManifest = file("app/src/benchmarkRelease/AndroidManifest.xml").readText()
        val productionSources = File(repositoryRoot, "app/src/main")
            .walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { source -> source.readText() }

        assertTrue("Benchmark fixture activity must exist only in benchmarkRelease", fixture.isFile)
        assertTrue(
            "Benchmark fixture must run outside the production app process",
            "android:process=\":benchmarkFixture\"" in benchmarkManifest,
        )
        assertTrue(
            "Benchmark fixture must retain an explicit benchmark-only action",
            "com.theoriacodex.action.BENCHMARK_FIXTURE" in benchmarkManifest,
        )
        listOf(
            "android.permission.INTERNET",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "androidx.core.content.FileProvider",
        ).forEach { inheritedSurface ->
            assertTrue(
                "Benchmark manifest must remove inherited $inheritedSurface",
                inheritedSurface in benchmarkManifest && "tools:node=\"remove\"" in benchmarkManifest,
            )
        }
        assertTrue(
            "Benchmark MainActivity must replace production deep links with launcher-only startup",
            "tools:node=\"replace\"" in benchmarkManifest &&
                "android.intent.action.MAIN" in benchmarkManifest &&
                "android.intent.action.VIEW" !in benchmarkManifest &&
                "android.intent.category.BROWSABLE" !in benchmarkManifest,
        )
        assertFalse(
            "Production manifest must be component-free",
            "BenchmarkFixtureActivity" in appManifest || ":benchmarkFixture" in appManifest,
        )
        assertFalse("Production Kotlin must not contain fixture implementation", "benchmarkPosts(" in productionSources)

        val fixtureText = fixture.readText()
        listOf(
            "Repository",
            "SourceAdapter",
            "Credentials",
            "HttpClient",
            "https://",
            "filesDir",
            "cacheDir",
        ).forEach { forbidden ->
            assertFalse("Benchmark fixture must not depend on $forbidden", forbidden in fixtureText)
        }
        assertTrue("Fixture media must be APK-local", "android.resource://" in fixtureText)
        assertTrue(
            "Offline fixture video must be bundled only with benchmarkRelease",
            file("app/src/benchmarkRelease/res/raw/benchmark_loop.mp4").length() > 1_024L,
        )
    }

    @Test
    fun `fixture process cannot start production container and main process remains eager`() {
        val appBuild = file("app/build.gradle.kts").readText()
        val application = file(
            "app/src/main/java/com/theoriacodex/app/TheoriaApplication.kt",
        ).readText()

        assertTrue(
            "Debug/release default must compile benchmark fixtures off",
            "buildConfigField(\"boolean\", \"BENCHMARK_FIXTURES_ENABLED\", \"false\")" in appBuild,
        )
        assertTrue(
            "Only benchmarkRelease may compile benchmark fixtures on",
            "buildConfigField(\"boolean\", \"BENCHMARK_FIXTURES_ENABLED\", \"true\")" in appBuild,
        )
        assertTrue(
            "Benchmark startup and fixture storage must use an isolated app sandbox",
            "applicationIdSuffix = \".benchmark\"" in appBuild,
        )
        assertTrue(
            "AGP variant API must add the fixture manifest only to benchmarkRelease",
            "withBuildType(\"benchmarkRelease\")" in appBuild &&
                "sources.manifests.addStaticManifestFile" in appBuild,
        )
        val artifactVerifier = file("scripts/verify_benchmark_fixture_apk.py").readText()
        listOf(
            "EXPECTED_APPLICATION_ID",
            "EXPECTED_ACTIVITY",
            "EXPECTED_ACTION",
            "EXPECTED_PROCESS",
            "benchmark_loop",
            "packaged benchmark video bytes differ",
            "FORBIDDEN_MANIFEST_SURFACES",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.intent.action.VIEW",
            "android.intent.category.BROWSABLE",
            "androidx.core.content.FileProvider",
        ).forEach { contract ->
            assertTrue("Packaged artifact verifier must retain $contract", contract in artifactVerifier)
        }
        assertTrue(
            "Benchmark assembly must finalize with packaged artifact verification",
            "finalizedBy(verifyBenchmarkFixtureArtifact)" in appBuild,
        )
        assertTrue(
            "Application startup must use the exact-process policy",
            "shouldStartAppContainer(" in application,
        )
        assertTrue(
            "Normal readiness must still start eagerly when policy allows",
            "startAppContainer()" in application,
        )
        assertTrue(
            "Fixture process identity must be exact rather than suffix-wide",
            "\"${'$'}packageName:benchmarkFixture\"" in application,
        )
    }

    @Test
    fun `macrobenchmark owner is isolated and enforces concurrent visible autoplay`() {
        val baselineBuild = file("baseline-profile/build.gradle.kts").readText()
        val macroBuild = file("macrobenchmark/build.gradle.kts").readText()
        val benchmark = file(
            "macrobenchmark/src/main/java/com/theoriacodex/macrobenchmark/TheoriaMacrobenchmark.kt",
        ).readText()

        assertTrue("Baseline profile generation keeps its dedicated rule", "BaselineProfile" in baselineBuild)
        assertTrue("Macrobenchmark module must use its own rule", "Macrobenchmark" in macroBuild)
        assertFalse(
            "Personal-device benchmarks must not enable or disable unrelated packages",
            "SideEffectRunListener" in macroBuild || "testInstrumentationRunnerArguments[\"listener\"]" in macroBuild,
        )
        val runnerVerifier = file("scripts/verify_macrobenchmark_runner_apk.py").readText()
        assertTrue(
            "Macrobenchmark assembly must verify the packaged runner manifest",
            "finalizedBy(verifyMacrobenchmarkRunnerArtifact)" in macroBuild &&
                "EXPECTED_RUNNER" in runnerVerifier &&
                "FORBIDDEN_MANIFEST_CONFIGURATION" in runnerVerifier,
        )
        assertTrue(
            "Packaged listener classes must be documented as inert rather than absent",
            "transitive SideEffectRunListener bytecode" in runnerVerifier,
        )
        assertTrue(
            "Macrobenchmark must use the plugin-managed benchmarkRelease target lane",
            "libs.plugins.androidx.baselineprofile" in macroBuild &&
                "targetProjectPath = \":app\"" in macroBuild,
        )
        assertTrue("Search must require two visible players", "videos.size >= 2" in benchmark)
        assertTrue(
            "Macrobenchmark must target the isolated release-like package",
            "TARGET_PACKAGE = \"com.theoriacodex.benchmark\"" in benchmark,
        )
        assertTrue("Search must require every visible player", "videos.all(UiObject2::reportsPlaying)" in benchmark)
        assertTrue("Search must recheck autoplay during scrolling", "repeat(SEARCH_SCROLL_STEPS)" in benchmark)
        assertFalse("Macrobenchmark must not tap videos to start playback", ".click()" in benchmark)
        assertTrue("Viewer must exercise forward and reverse swipes", "device.swipeViewer(next = true)" in benchmark)
        assertTrue("Fixture waits must be bounded", "UI_TIMEOUT_MS = 15_000L" in benchmark)
        assertTrue(
            "Interaction setup must own launch and readiness outside the measured block",
            benchmark.lines().windowed(size = 2).count { lines ->
                lines[0].contains("compilationMode = COMPILATION_MODE") &&
                    lines[1].contains("startupMode = null")
            } == 2,
        )
        listOf(
            "previewPrepare",
            "previewFirstFrame",
            "viewerPrepare",
            "viewerFirstFrame",
            "mediaLoad",
        ).forEach { metricLabel ->
            assertFalse(
                "Count metric labels must not duplicate the Count suffix",
                "\"${metricLabel}Count\"" in benchmark,
            )
        }
    }

    @Test
    fun `interaction metrics retain frame memory and comparable media counters`() {
        val benchmark = file(
            "macrobenchmark/src/main/java/com/theoriacodex/macrobenchmark/TheoriaMacrobenchmark.kt",
        ).readText()
        val media = file(
            "app/src/main/java/com/theoriacodex/app/viewer/ExoVideoComponents.kt",
        ).readText()
        val search = file(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
        ).readText()
        val viewer = file(
            "app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt",
        ).readText()

        assertTrue("Interaction benchmarks must record frames", "FrameTimingMetric(" in benchmark)
        assertTrue("Interaction benchmarks must record peak process memory", "MemoryUsageMetric.Mode.Max" in benchmark)
        assertTrue(
            "Frame and memory metrics must target the isolated fixture process",
            "processNameSuffix = FIXTURE_PROCESS_SUFFIX" in benchmark,
        )
        listOf(
            "TheoriaPreviewPrepare",
            "TheoriaPreviewFirstFrame",
            "TheoriaViewerPrepare",
            "TheoriaViewerFirstFrame",
            "TheoriaMediaLoad",
        ).forEach { traceName ->
            assertTrue("Macrobenchmark must retain $traceName", traceName in benchmark)
            assertTrue("App instrumentation must retain $traceName", traceName in media)
        }
        assertTrue("Search semantics must expose actual player state", "onIsPlayingChanged" in search)
        assertTrue("Viewer semantics must expose actual player state", "onIsPlayingChanged" in viewer)
        assertTrue(
            "Search playback announcements must be diagnostics-gated",
            "playbackDiagnosticsSemantics(" in search,
        )
        assertTrue(
            "Viewer playback announcements must be diagnostics-gated",
            "playbackDiagnosticsSemantics(" in viewer,
        )
        assertFalse(
            "Search benchmark announcement must not expose provider IDs",
            "Playing Search video ${'$'}{postId.sourcePostId}" in search,
        )
        assertFalse(
            "Viewer benchmark announcement must not expose provider IDs",
            "Playing Viewer video ${'$'}{postId.sourcePostId}" in viewer,
        )
        val normalCallSites = File(repositoryRoot, "app/src/main")
            .walkTopDown()
            .filter(File::isFile)
            .filter { source -> source.extension == "kt" }
            .joinToString("\n") { source -> source.readText() }
        assertFalse(
            "Normal Search/Viewer routes must not enable playback diagnostics",
            "playbackDiagnosticsEnabled = true" in normalCallSites,
        )
    }

    private fun file(relativePath: String): File = File(repositoryRoot, relativePath)
}
