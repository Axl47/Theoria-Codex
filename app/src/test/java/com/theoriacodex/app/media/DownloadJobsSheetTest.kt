package com.theoriacodex.app.media

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloadJobsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `downloads use two columns origin labels and count with tap and distinct individual and bulk actions`() {
        val items = listOf(item(2, "Search"), item(3, "Codex · Favorites"))
        var opened: Long? = null
        val removals = mutableListOf<Pair<Set<Long>, Boolean>>()
        compose.setContent { TheoriaNightTheme {
            DownloadJobsSheet(
                batches = listOf(PostDownloadBatch(1, "Old batch title", items)),
                onCancel = {}, onRetryFailed = {}, onOpen = { opened = it.id },
                onRemove = { ids, files -> removals += ids to files }, localFile = { null }, onDismiss = {},
            )
        } }
        compose.onNodeWithTag("download-count").assertExists()
        compose.onNodeWithText("Old batch title").assertDoesNotExist()
        compose.onNodeWithText("Keep the app open", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Saved", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Pixiv · Search").assertExists()
        compose.onNodeWithText("Pixiv · Codex · Favorites").assertExists()
        val first = compose.onNodeWithTag("download:2").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("download:3").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(first.left < second.left)
        compose.onNodeWithTag("download:2").performClick()
        assertEquals(2L, opened)
        compose.onNodeWithTag("download:2").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Remove from Downloads").performClick()
        assertEquals(listOf(setOf(2L) to false), removals)
        compose.onNodeWithTag("download:3").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Delete file", substring = false).performClick()
        assertEquals(1, removals.size)
        compose.onNodeWithText("Delete files", substring = false).performClick()
        assertEquals(setOf(3L) to true, removals.last())
        compose.onNodeWithContentDescription("Manage all downloads").performClick()
        compose.onNodeWithText("Remove all from Downloads").performClick()
        assertEquals(setOf(2L, 3L) to false, removals.last())
        compose.onNodeWithContentDescription("Manage all downloads").performClick()
        compose.onNodeWithText("Delete all files").performClick()
        assertEquals(3, removals.size)
        compose.onNodeWithText("Delete files", substring = false).performClick()
        assertEquals(setOf(2L, 3L) to true, removals.last())
    }

    private fun item(id: Long, origin: String) = PostDownloadItem(
        id, PostDownloadRequest(testPost(sourcePostId = "$id", title = "Post $id", preview = ImageRef(null, null, null)),
            originLabel = origin), PostDownloadStatus.SAVED,
    )
}
