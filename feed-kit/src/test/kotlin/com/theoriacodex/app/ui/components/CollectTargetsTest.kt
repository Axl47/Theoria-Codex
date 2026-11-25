package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectTargetsTest {
    @get:Rule val compose = createComposeRule()

    private val targets = listOf("first", "second").map { CollectTarget(it, it, CollectTargetVisual.Icon(Icons.Outlined.CollectionsBookmark), it) }

    private fun fillOf(key: String): Color = compose.onNodeWithTag("Collect target $key").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 5] }

    @Test fun `the hovered target takes the armed fill`() {
        val pointer = mutableStateOf<Offset?>(null)
        compose.setContent {
            MaterialTheme(lightColorScheme(primaryContainer = Color.Red, surfaceContainerHigh = Color.Blue)) {
                CollectTargets(targets, pointer.value, CollectTargetsLayout.EdgeColumn())
            }
        }
        val first = compose.onNodeWithTag("Collect target first").fetchSemanticsNode().boundsInWindow.center
        compose.runOnIdle { pointer.value = first }
        compose.waitForIdle()
        assertEquals(Color.Red, fillOf("first"))
        assertEquals(Color.Blue, fillOf("second"))
    }
}
