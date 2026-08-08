package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouPostActionsArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `For You hold tap opens the shared post action surface`() {
        val screen = file(
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
        ).readText()

        assertTrue("Recommendation cards must own a hold-tap action", "onLongPress = { selectedActionPost = post }" in screen)
        assertTrue("For You must use the shared post action sheet", "PostActionSheet(" in screen)
        assertTrue("For You actions must expose the shared tag controls", "PostTagActionSection(" in screen)
    }

    private fun file(relativePath: String): File = File(repositoryRoot, relativePath)
}
