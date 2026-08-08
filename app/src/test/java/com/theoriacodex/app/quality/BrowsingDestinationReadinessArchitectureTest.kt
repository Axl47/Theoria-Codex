package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsingDestinationReadinessArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `browsing routes wait for repository snapshots instead of consuming placeholders`() {
        val boundaries = File(
            repositoryRoot,
            "app/src/main/java/com/theoriacodex/app/ui/routes/DestinationStateBoundaries.kt",
        ).readText()
        val browsingBoundary = boundaries.substringAfter("fun BrowsingDestinationStateBoundary(")
            .substringBefore("fun RecentsDestinationStateBoundary(")

        assertTrue(
            "Browsing settings must use a nullable loading sentinel",
            "observeSettings()\n        .collectAsStateWithLifecycle(initialValue = null)" in browsingBoundary,
        )
        assertTrue(
            "Browsing likes must use nullable loading sentinels",
            "observeLikedPostIds(activeProfile.profileId)\n            .collectAsStateWithLifecycle(initialValue = null)" in browsingBoundary &&
                "observeLikes(activeProfile.profileId)\n            .collectAsStateWithLifecycle(initialValue = null)" in browsingBoundary,
        )
        assertFalse(
            "An empty likes placeholder must not clear the retained For You feed",
            "observeLikes(activeProfile.profileId)\n            .collectAsStateWithLifecycle(initialValue = emptyList())" in browsingBoundary,
        )
    }
}
