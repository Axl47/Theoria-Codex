package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source/build isolation guard only. Packaged APK verification and device journeys prove runtime behavior. */
class PerformanceBenchmarkArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `every connected development app identity is isolated from production`() {
        val appBuild = file("app/build.gradle.kts").readText()
        val baselineBuild = file("baseline-profile/build.gradle.kts").readText()
        val baselineGenerator = file(
            "baseline-profile/src/main/java/com/theoriacodex/baselineprofile/" +
                "BaselineProfileGenerator.kt",
        ).readText()
        val macrobenchmarkBuild = file("macrobenchmark/build.gradle.kts").readText()
        val debugStrings = file("app/src/debug/res/values/strings.xml").readText()

        listOf(
            "debug" to ".debug",
            "releaseAcceptance" to ".acceptance",
            "benchmarkRelease" to ".benchmark",
            "nonMinifiedRelease" to ".baselineprofile",
        ).forEach { (variant, suffix) ->
            assertTrue(
                "$variant must retain a non-production application ID",
                "\"$variant\" to \"com.theoriacodex$suffix\"" in appBuild &&
                    "applicationIdSuffix = \"$suffix\"" in appBuild,
            )
        }
        assertTrue(
            "Every installable development APK must verify packaged output metadata",
            "VerifyInstallableApplicationIdTask" in appBuild &&
                "dependsOn(\"package${'$'}capitalizedVariant\")" in appBuild &&
                "connected${'$'}{capitalizedVariant}AndroidTest" in appBuild &&
                "install${'$'}capitalizedVariant" in appBuild,
        )
        assertTrue(
            "Baseline-profile collection must target only its isolated sandbox",
            "PACKAGE_NAME = \"com.theoriacodex.baselineprofile\"" in baselineGenerator &&
                "verifyNonMinifiedReleaseInstallableApplicationId" in baselineBuild,
        )
        assertTrue(
            "Baseline-profile test and target APKs must keep distinct isolated identities",
            "namespace = \"com.theoriacodex.baselineprofile.test\"" in baselineBuild &&
                "VerifyBaselineProfileTestApplicationIdTask" in baselineBuild &&
                "verifyNonMinifiedReleaseTestApplicationId" in baselineBuild &&
                "expectedApplicationId.set(\"com.theoriacodex.baselineprofile.test\")" in baselineBuild,
        )
        assertFalse(
            "Baseline-profile collection must never target production",
            "PACKAGE_NAME = \"com.theoriacodex\"" in baselineGenerator,
        )
        assertTrue(
            "Macrobenchmark connected execution must prove its packaged target first",
            "verifyBenchmarkReleaseInstallableApplicationId" in macrobenchmarkBuild,
        )
        assertTrue(
            "Debug must be visually distinguishable from the production launcher",
            "<string name=\"app_name\">Theoria Debug</string>" in debugStrings,
        )
    }

    @Test
    fun `benchmark manifest removes production network installation and link entry points`() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file("app/src/benchmarkRelease/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        val permissions = document.getElementsByTagName("uses-permission")
        listOf("android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES").forEach { permission ->
            val nodes = (0 until permissions.length).map { permissions.item(it) as org.w3c.dom.Element }
            assertTrue(nodes.any { it.getAttributeNS(android, "name") == permission &&
                it.getAttributeNS(tools, "node") == "remove" })
        }
        val activities = document.getElementsByTagName("activity")
        val fixture = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".benchmark.BenchmarkFixtureActivity" }
        assertTrue(fixture.getAttributeNS(android, "process") == ":benchmarkFixture")
        val actions = document.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        assertFalse("android.intent.action.VIEW" in names)
    }

    @Test
    fun `personal device runner retains no package mutating listener`() {
        val build = file("macrobenchmark/build.gradle.kts").readText()
        assertFalse("SideEffectRunListener" in build)
        assertFalse("testInstrumentationRunnerArguments[\"listener\"]" in build)
        assertTrue("finalizedBy(verifyMacrobenchmarkRunnerArtifact)" in build)
    }

    private fun file(relativePath: String): File = File(repositoryRoot, relativePath)
}
