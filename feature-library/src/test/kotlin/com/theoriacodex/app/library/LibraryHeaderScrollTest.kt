package com.theoriacodex.app.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.ScrollChromeBox
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryHeaderScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a short section that fits once the header hides keeps its last row in view at the end (ADR 0218)`() {
        setSection { maxHeight -> maxHeight - 60.dp }
        val screenBottom = compose.onRoot().getUnclippedBoundsInRoot().bottom

        repeat(2) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
            val lastRow = compose.onNodeWithTag(LAST_ROW_TAG).getUnclippedBoundsInRoot()
            assertTrue("last row ends at ${lastRow.bottom}, past the screen's $screenBottom", lastRow.bottom <= screenBottom)
            compose.onNodeWithTag(LIBRARY_SETTINGS_TAG).assertIsNotDisplayed()
        }
    }

    @Test
    fun `a section that shrinks to fit under the shown header brings a hidden header back (ADR 0218)`() {
        var short by mutableStateOf(false)
        setSection { maxHeight -> if (short) LAST_ROW * 2 else maxHeight * 2 }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag(LIBRARY_SETTINGS_TAG).assertIsNotDisplayed()

        short = true
        compose.waitForIdle()
        compose.onNodeWithTag(LIBRARY_SETTINGS_TAG).assertIsDisplayed()
    }

    private fun setSection(contentHeight: (Dp) -> Dp) {
        compose.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val height = contentHeight(maxHeight)
                    ScrollChromeBox(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            LibraryHeader(LibrarySection.Saved, {}, {}, emptySet(), actions = {})
                            val list = rememberLazyListState()
                            ScrollToTopOnTabReselect(TopLevelDestination.Library, list)
                            LazyColumn(Modifier.fillMaxSize(), state = list) {
                                item { Box(Modifier.fillMaxWidth().height(height - LAST_ROW)) }
                                item { Box(Modifier.fillMaxWidth().height(LAST_ROW).testTag(LAST_ROW_TAG)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val LAST_ROW = 140.dp
        const val LAST_ROW_TAG = "last-row"
    }
}
