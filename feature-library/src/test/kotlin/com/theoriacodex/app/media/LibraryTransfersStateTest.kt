package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.domain.model.ImageRef
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTransfersStateTest {
    @Test fun `only Codices kept offline get a ring, filled by their current posts that are settled`() {
        val (a, b, c, d) = listOf("a", "b", "c", "d").map { testPost(sourcePostId = it).id }
        val owners = listOf(
            OfflineStorageOwner("codex:full", "Full", 2),
            OfflineStorageOwner("codex:half", "Half", 1, job = OfflineMediaJob(totalPosts = 2, completedPosts = 1)),
            OfflineStorageOwner("codex:failed", "Failed", 1, job = OfflineMediaJob(2, 1, failedPosts = setOf(c), isRunning = false)),
            OfflineStorageOwner("codex:shrunk", "Shrunk", 1),
            OfflineStorageOwner("codex:unknown", "Unknown", 1),
            OfflineStorageOwner(AUTOMATIC_OFFLINE_OWNER, "Saved posts", 9),
        )
        val members = mapOf("full" to setOf(a, b), "half" to setOf(a, d), "failed" to setOf(a, c), "shrunk" to setOf(d))
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

    @Test fun `the header counts posts still to save across offline jobs and downloads`() {
        val offline = OfflineStorageUiState(owners = listOf(
            OfflineStorageOwner("codex:a", "A", 2, job = OfflineMediaJob(totalPosts = 10, completedPosts = 2)),
            OfflineStorageOwner("codex:b", "B", 5),
        ))
        val batches = listOf(PostDownloadBatch(1, "Batch", listOf(download(1, PostDownloadStatus.DOWNLOADING), download(2, PostDownloadStatus.SAVED))))
        assertEquals(LibraryTransfersStatus(active = true, remaining = 9, hasContent = true), libraryTransfersStatus(offline, batches))
        assertEquals(LibraryTransfersStatus(active = false, remaining = 0, hasContent = false), libraryTransfersStatus(OfflineStorageUiState(), emptyList()))
    }

    @Test fun `removed downloads hide at once, undo shows them, and expiry commits the removal`() = runTest {
        val commits = mutableListOf<Pair<Set<Long>, Boolean>>()
        val undo = UndoSlot()
        val removals = DownloadRemovals({ ids, files -> commits += ids to files }, backgroundScope)

        removals.remove(undo, setOf(1L), deleteFiles = true, downloadUndoAnchor(1))
        assertEquals(setOf(1L), removals.hidden.value)
        assertEquals("Deleted file", undo.offer.value?.label)
        undo.act(checkNotNull(undo.offer.value))
        runCurrent()
        assertEquals(emptySet<Long>(), removals.hidden.value)
        assertTrue(commits.isEmpty())

        removals.remove(undo, setOf(1L, 2L), deleteFiles = false, DownloadsUndoAnchor)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(listOf(setOf(1L, 2L) to false), commits)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(emptySet<Long>(), removals.hidden.value)
    }

    private fun download(id: Long, status: PostDownloadStatus) = PostDownloadItem(
        id, PostDownloadRequest(testPost(sourcePostId = "$id", preview = ImageRef(null, null, null)), originLabel = "Viewer"), status,
    )
}
