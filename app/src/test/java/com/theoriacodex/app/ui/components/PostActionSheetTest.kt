package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.testing.testPost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostActionSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `search terms open in place while other actions close the menu`() {
        var dismissals = 0
        var deviceSaves = 0
        compose.setContent {
            MaterialTheme {
                PostActionSheet(
                    post = testPost(),
                    onDismiss = { dismissals++ },
                    onSaveToDevice = { deviceSaves++ },
                    tagContent = { Text("Tag list") },
                )
            }
        }

        compose.onNodeWithText("Tag list").assertDoesNotExist()
        compose.onNodeWithText("Save to device").performClick()
        compose.runOnIdle { assertEquals(1 to 1, dismissals to deviceSaves) }

        compose.onNodeWithText("Search terms").performClick()
        compose.onNodeWithText("Tag list").assertIsDisplayed()
        compose.onNodeWithText("Save to device").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
