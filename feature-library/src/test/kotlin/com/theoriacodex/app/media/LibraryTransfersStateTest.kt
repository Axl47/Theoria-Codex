package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.DEFAULT_UNDO_DURATION
import com.theoriacodex.domain.model.ImageRef
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class LibraryTransfersStateTest {
    @Test fun `only Codices kept offline get a ring, filled by retained posts over their post count`() {
        val owners = listOf(
            OfflineStorageOwner("codex:full", "Full", 4),
            OfflineStorageOwner("codex:half", "Half", 2, job = OfflineMediaJob(totalPosts = 4, completedPosts = 2)),
            OfflineStorageOwner("codex:failed", "Failed", 1, job = OfflineMediaJob(4, 1, isRunning = false, failureReason = "x")),
            OfflineStorageOwner("codex:unknown", "Unknown", 1),
            OfflineStorageOwner(AUTOMATIC_OFFLINE_OWNER, "Saved posts", 9),
        )
        val states = codexOfflineStates(owners, mapOf("full" to 4, "half" to 4, "failed" to 4))
        assertEquals(
            mapOf(
                "full" to CodexOfflineState(1f),
                "half" to CodexOfflineState(0.5f, running = true),
                "failed" to CodexOfflineState(0.25f, failed = true),
            ),
            states,
        )
        assertTrue(states.getValue("full").complete)
    }

    @Test fun `the header counts posts still to save across offline jobs and downloads`() {
        val offline = OfflineStorageUiState(owners = listOf(
            OfflineStorageOwner("codex:a", "A", 2, job = OfflineMediaJob(totalPosts = 10, completedPosts = 2)),
            OfflineStorageOwner("codex:b", "B", 5),
        ))
        val batches = listOf(PostDownloadBatch(1, "Batch", listOf(download(1, PostDownloadStatus.DOWNLOADING), download(2, PostDownloadStatus.SAVED))))
        assertEquals(LibraryTransfersStatus(active = true, remaining = 9, hasContent = true), libraryTransfersStatus(offline, batches))
        assertEquals(LibraryTransfersStatus(active = false, remaining = 0, hasContent = false), libraryTransfersStatus(OfflineStorageUiState(), emptyList()))
    }

    @Test fun `a download removal waits under its label, then commits its items and file choice`() = runTest {
        val commits = mutableListOf<Pair<Set<Long>, Boolean>>()
        val removals = DownloadRemovals({ ids, files -> commits += ids to files; true }, backgroundScope)

        removals.remove(setOf(1L), deleteFiles = true, downloadUndoAnchor(1))
        removals.remove(setOf(2L, 3L), deleteFiles = false, DownloadsUndoAnchor)
        assertEquals(
            listOf("Deleted file", "Removed 2 downloads"),
            removals.pending.value.values.map { it.label },
        )
        advanceTimeBy(DEFAULT_UNDO_DURATION + 1.milliseconds)
        runCurrent()
        assertEquals(listOf(setOf(1L) to true, setOf(2L, 3L) to false), commits)
        assertTrue(removals.pending.value.isEmpty())
    }

    private fun download(id: Long, status: PostDownloadStatus) = PostDownloadItem(
        id, PostDownloadRequest(testPost(sourcePostId = "$id", preview = ImageRef(null, null, null)), originLabel = "Viewer"), status,
    )
}
