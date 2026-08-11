package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRecentsRecordingSourceTest {
    private val repositoryRoot = generateSequence(
        requireNotNull(System.getProperty("user.dir")) { "The JVM did not expose user.dir" }
            .let(::File)
            .absoluteFile,
        File::getParentFile,
    ).firstOrNull { root -> File(root, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root")

    @Test
    fun `visible post recording uses the Viewer routes authoritative session`() {
        val route = source("app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt")
        val shell = source("app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt")
        val boundary = source("app/src/main/java/com/theoriacodex/app/ui/routes/DestinationStateBoundaries.kt")

        assertTrue(
            "Viewer callbacks must carry progress and the active route session",
            "(Post, Int, ViewerSession)" in route,
        )
        assertTrue(
            "ViewerRoute must pair the visible post with its current owner session",
            "latestScreenCallbacks.value.onVisiblePostChanged(" in route &&
                "currentSession" in route,
        )
        assertTrue(
            "The shell must record using the session delivered with the visible post",
            "onVisiblePostChanged = { post, viewedMediaNumber, session ->" in shell &&
                "viewerRouteWorkflow.recordVisiblePost(" in shell,
        )
        assertTrue(
            "Media changes must use the progress-only path",
            "onVisibleMediaChanged = { post, viewedMediaNumber, session ->" in shell &&
                "viewerRouteWorkflow.recordVisibleMediaProgress(" in shell,
        )
        assertFalse(
            "The outer destination snapshot must not own Viewer session recording",
            "recordVisiblePost(post, state.session)" in shell,
        )
        val viewerState = boundary.substringAfter("internal data class ViewerDestinationState(")
            .substringBefore(")\n")
        assertFalse("ViewerDestinationState must not duplicate route session ownership", "session:" in viewerState)
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()
}
