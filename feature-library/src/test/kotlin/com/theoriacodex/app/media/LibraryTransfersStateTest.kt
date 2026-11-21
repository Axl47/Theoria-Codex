package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.DEFAULT_UNDO_DURATION
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.domain.model.ImageRef
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class LibraryTransfersStateTest {
    @Test fun `only Codices kept offline get a ring, filled by their current posts that are settled`() {
        val (a, b, c, d) = listOf("a", "b", "c", "d").map { testPost(sourcePostId = it).id }
        val owners = listOf(
            OfflineStorageOwner("codex:full", "Full", 2),
            OfflineStorageOwner("codex:half", "Half", 1, job = OfflineMediaJob(totalPosts = 2, completedPosts = 1)),
            OfflineStorageOwner("codex:failed", "Failed", 1, job = OfflineMediaJob(2, 1, failedPosts = setOf(c), isRunning = false)),
            OfflineStorageOwner("codex:shrunk", "Shrunk", 1),
            OfflineStorageOwner("codex:unknown", "Unknown", 1),
            OfflineStorageOwner("codex:stopped", "Stopped", 0, job = OfflineMediaJob(2, isRunning = false, isCancelled = true)),
            OfflineStorageOwner(AUTOMATIC_OFFLINE_OWNER, "Saved posts", 9),
        )
        val members = mapOf("full" to setOf(a, b), "half" to setOf(a, d), "failed" to setOf(a, c), "shrunk" to setOf(d),
            "stopped" to setOf(c, d))
        val states = codexOfflineStates(owners, members, available = setOf(a, b))
        assertEquals(
            mapOf(
                "full" to CodexOfflineState(1f),
                "half" to CodexOfflineState(0.5f, running = true),
                "failed" to CodexOfflineState(1f, failed = true),
                "shrunk" to CodexOfflineState(0f),
            ),
            states,
        )
        assertTrue(states.getValue("full").complete)
    }

    @Test fun `a failed post that left the Codex does not mark the ring failed, a failed run does`() {
        val (a, gone) = listOf("a", "gone").map { testPost(sourcePostId = it).id }
        val owners = listOf(
            OfflineStorageOwner("codex:left", "Left", 1, job = OfflineMediaJob(2, 1, failedPosts = setOf(gone), isRunning = false)),
            OfflineStorageOwner("codex:run", "Run", 1, job = OfflineMediaJob(1, 1, isRunning = false, failureReason = "Blocked")),
        )
        val states = codexOfflineStates(owners, mapOf("left" to setOf(a), "run" to setOf(a)), available = setOf(a))
        assertEquals(mapOf("left" to CodexOfflineState(1f), "run" to CodexOfflineState(1f, failed = true)), states)
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

    @Test fun `a removed gallery that expands into pages keeps one Undo row in its place and hides the pages`() {
        val removal = DownloadRemoval(setOf(1L), deleteFiles = false, downloadUndoAnchor(1))
        val pages = (2L..4L).map { download(it, PostDownloadStatus.QUEUED).copy(expandedFrom = 1L) }
        val batches = listOf(PostDownloadBatch(10, "Gallery", pages + download(5, PostDownloadStatus.SAVED)))

        val content = TransfersSheetContent(OfflineStorageUiState(), batches, mapOf(removal to PendingRemoval("Removed")))

        assertEquals(listOf(2L, 5L), content.shownItems.map { it.id })
        assertEquals(mapOf(2L to removal), content.tileRemovals)
        assertEquals(listOf(5L), content.visibleItems.map { it.id })
    }

    private fun download(id: Long, status: PostDownloadStatus) = PostDownloadItem(
        id, PostDownloadRequest(testPost(sourcePostId = "$id", preview = ImageRef(null, null, null)), originLabel = "Viewer"), status,
    )
}
