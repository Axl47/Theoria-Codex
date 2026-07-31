package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoilUpgradeDecisionArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `Coil 2 stays pinned until protected media device acceptance is rerun`() {
        val catalog = file("gradle/libs.versions.toml").readText()

        assertTrue("The accepted Coil 2 baseline must remain explicit", "coil = \"2.7.0\"" in catalog)
        assertTrue("Coil 2 artifacts must retain their established coordinates", "group = \"io.coil-kt\"" in catalog)
        assertFalse(
            "A Coil 3 coordinate requires a deliberate contract-test and device-evidence update",
            "io.coil-kt.coil3" in catalog,
        )
    }

    @Test
    fun `upgrade gate retains every protected header and animated media seam`() {
        val application = file("app/src/main/java/com/theoriacodex/app/TheoriaApplication.kt").readText()
        val requests = file("app/src/main/java/com/theoriacodex/app/media/MediaRequestFactory.kt").readText()
        val decoder = file("app/src/main/java/com/theoriacodex/app/media/LegacyAnimatedWebPDecoder.kt").readText()
        val viewer = file("app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt").readText()
        val deviceAcceptance = file(
            "app/src/androidTest/java/com/theoriacodex/app/media/AnimatedWebPDecoderDeviceTest.kt",
        ).readText()

        assertTrue("ImageLoader ownership must remain application-scoped", "ImageLoaderFactory" in application)
        assertTrue("The legacy controllable WebP decoder must remain registered", "LegacyAnimatedWebPDecoder.Factory()" in application)
        assertTrue("Source headers must be attached to each request", "sourceKey.requestHeaders().forEach" in requests)
        assertTrue("Protected headers must remain on the request builder", "builder.addHeader(name, value)" in requests)
        assertTrue("Animated decode mode must remain cache-keyed per request", "memoryCacheKey = mode.memoryCacheKey" in requests)
        assertTrue("The legacy decoder must read the same request parameter", "options.parameters.value" in decoder)
        assertTrue("Viewer animation control still depends on Drawable conversion", "ScaleDrawable" in viewer)
        listOf(
            "context.imageLoader.execute(normalRequest)",
            "context.imageLoader.execute(staticRequest)",
            "context.imageLoader.execute(controllableRequest)",
            "AnimatedImageDrawable",
            "BitmapDrawable",
            "WebPDrawable",
        ).forEach { contract ->
            assertTrue("Device acceptance must retain $contract", contract in deviceAcceptance)
        }
    }

    private fun file(path: String): File = File(repositoryRoot, path)
}
