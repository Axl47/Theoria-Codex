package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionableFeedbackArchitectureTest {
    private val repositoryRoot = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
        File::getParentFile,
    ).firstOrNull { root -> File(root, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `app shell owns the only snackbar host while passive messages remain toasts`() {
        val shell = source("app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt")
        val production = File(repositoryRoot, "app/src/main/java")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .joinToString("\n") { file -> file.readText() }

        assertEquals("The app must have exactly one rendered snackbar host", 1, production.count("SnackbarHost("))
        assertTrue("The outer Scaffold must render the snackbar host", "snackbarHost = { SnackbarHost(snackbarHostState) }" in shell)
        assertTrue("Passive confirmations must remain on Toast", "Toast.makeText" in shell)
        assertFalse(
            "Recents must request feedback from the shell instead of rendering a host",
            "SnackbarHost(" in source("app/src/main/java/com/theoriacodex/app/recents/RecentsScreen.kt"),
        )
        assertFalse(
            "For You must request feedback from the shell instead of rendering a host",
            "SnackbarHost(" in source("app/src/main/java/com/theoriacodex/app/ui/routes/ForYouRoute.kt"),
        )
    }

    private fun source(path: String): String = File(repositoryRoot, path).readText()

    private fun String.count(needle: String): Int = windowed(needle.length).count { it == needle }
}
