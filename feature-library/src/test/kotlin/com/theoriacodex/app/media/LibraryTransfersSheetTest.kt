package com.theoriacodex.app.media

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.seriesOfflineOwner
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryTransfersSheetTest {
    @get:Rule val compose = createComposeRule()
    private val removals = mutableListOf<Triple<Set<Long>, Boolean, UndoAnchor>>()
    private val offlineCalls = mutableListOf<String>()

    @Test fun `the running pill is one button a screen reader can activate`() {
        var opened = 0
        compose.setContent { TheoriaNightTheme { LibraryTransfersButton(LibraryTransfersStatus(true, 3, true)) { opened++ } } }
        compose.onNodeWithContentDescription("Offline and downloads, 3 left").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun `download tiles sit two to a row and act from their own menu without confirm dialogs`() {
        var opened: Long? = null
        show(downloads = listOf(item(2, "Search"), item(3, "Codex · Favorites")), onOpen = { opened = it })
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
        compose.onAllNodesWithContentDescription("Manage download")[1].performClick()
        compose.onNodeWithText("Delete file").performClick()
        compose.onNodeWithContentDescription("Manage all downloads").performClick()
        compose.onNodeWithText("Delete all files").performClick()
        assertEquals(
            listOf(
                Triple(setOf(2L), false, downloadUndoAnchor(2)),
                Triple(setOf(3L), true, downloadUndoAnchor(3)),
                Triple(setOf(2L, 3L), true, DownloadsUndoAnchor),
            ),
            removals,
        )
    }

    @Test fun `offline rows come first with cancel on the row and removal on its menu`() {
        val running = OfflineStorageOwner("codex:a", "Favorites", 3, job = OfflineMediaJob(totalPosts = 5, completedPosts = 3))
        show(owners = listOf(running), downloads = listOf(item(2, "Search")))
        val offline = compose.onNodeWithTag("offline-owner:codex:a").fetchSemanticsNode().boundsInRoot
        val download = compose.onNodeWithTag("download:2").fetchSemanticsNode().boundsInRoot
        assertTrue(offline.bottom <= download.top)
        compose.onNodeWithText("Saving 3 of 5").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Manage Favorites").performClick()
        compose.onNodeWithText("Remove offline copies").performClick()
        assertEquals(listOf("cancel:codex:a", "remove:codex:a"), offlineCalls)
    }

    @Test fun `a row opens the Codex or series it keeps offline`() {
        val series = SeriesId(SourceKey.GELBOORU, "/series/1")
        show(owners = listOf(OfflineStorageOwner(seriesOfflineOwner(series), "Series", 2), OfflineStorageOwner(codexOfflineOwner("a"), "Codex", 1)))
        compose.onNodeWithText("Series").performClick()
        compose.onNodeWithText("Codex").performClick()
        assertEquals(listOf("series:/series/1", "codex:a"), offlineCalls)
    }

    @Test fun `opened for a Codex it scrolls to that row once the owners have loaded`() {
        val owners = mutableStateOf(emptyList<OfflineStorageOwner>())
        show(owners = owners, markedCodexId = "z")
        compose.onNodeWithTag("offline-owner:codex:z").assertDoesNotExist()
        owners.value = ('a'..'z').map { OfflineStorageOwner("codex:$it", "Codex $it", 1) }
        compose.onNodeWithTag("offline-owner:codex:z").assertIsDisplayed()
    }

    @Test fun `a download without a title is named by its source, never its post id`() {
        val untitled = item(4, "Search").let { it.copy(request = it.request.copy(post = it.request.post.copy(title = null))) }
        show(downloads = listOf(untitled))
        compose.onNodeWithText("Pixiv").assertExists()
        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithText("Post 4", substring = true).assertDoesNotExist()
    }

    @Test fun `an empty sheet says so`() {
        show()
        compose.onNodeWithText("Nothing offline or downloaded").assertExists()
    }

    private fun show(
        owners: List<OfflineStorageOwner> = emptyList(),
        downloads: List<PostDownloadItem> = emptyList(),
        onOpen: (Long) -> Unit = {},
    ) = show(mutableStateOf(owners), downloads, onOpen)

    private fun show(
        owners: State<List<OfflineStorageOwner>>,
        downloads: List<PostDownloadItem> = emptyList(),
        onOpen: (Long) -> Unit = {},
        markedCodexId: String? = null,
    ) {
        compose.setContent { TheoriaNightTheme {
            LibraryTransfersSheet(
                offline = OfflineStorageUiState(owners = owners.value),
                offlineActions = OfflineRowActions(
                    retry = { offlineCalls += "retry:$it" }, cancel = { offlineCalls += "cancel:$it" },
                    remove = { offlineCalls += "remove:$it" }, removeAll = { offlineCalls += "removeAll" },
                    actOnRemoval = { offlineCalls += "act:$it" },
                    openCodex = { offlineCalls += "codex:$it" },
                    openSeries = { offlineCalls += "series:${it.address}" },
                ),
                batches = if (downloads.isEmpty()) emptyList() else listOf(PostDownloadBatch(1, "Batch", downloads)),
                downloadRemovals = DownloadRemovalRows(emptyMap()) {},
                downloadActions = DownloadTileActions(
                    open = { onOpen(it.id) }, cancel = {}, retryFailed = {},
                    remove = { ids, files, anchor -> removals += Triple(ids, files, anchor) }, localFile = { null },
                ),
                markedCodexId = markedCodexId,
                onDismiss = {},
                onLeave = {},
            )
        } }
    }

    private fun item(id: Long, origin: String) = PostDownloadItem(
        id, PostDownloadRequest(testPost(sourcePostId = "$id", title = "Post $id", preview = ImageRef(null, null, null)),
            originLabel = origin), PostDownloadStatus.SAVED,
    )
}
