package com.theoriacodex.app.statistics

import com.theoriacodex.data.repository.CodexUsageTarget
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.StatisticsThumbnailStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailStatisticsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a post seen before the first snapshot keeps retained thumbnails`() = runTest {
        val delegate = InMemoryStatisticsRepository()
        delegate.recordCodexPostOpen("codex", "PIXIV:42", "Post")
        val store = StatisticsThumbnailStore(temporary.newFolder())
        store.reconcile(setOf("PIXIV:42"), mapOf("PIXIV:42" to { file -> file.writeText("image"); true }))
        val repository = ThumbnailStatisticsRepository(delegate, backgroundScope, store) { _, _ -> false }

        repository.recordPostAvailable(post("7"))
        runCurrent()

        assertTrue(repository.observeStatistics().first().thumbnails.containsKey("PIXIV:42"))
    }

    @Test fun `fetch failure never blocks recording and only promotion or available post retries`() = runTest {
        val delegate = InMemoryStatisticsRepository()
        var requests = 0
        var offline = true
        val repository = ThumbnailStatisticsRepository(delegate, backgroundScope,
            StatisticsThumbnailStore(temporary.newFolder())) { _, file ->
            requests++
            if (offline) throw IOException("offline")
            file.writeText("image")
            true
        }
        runCurrent()
        val post = post("42")
        repository.recordPostAvailable(post)
        runCurrent()
        assertEquals(0, requests)
        repository.recordCodexPostOpen("codex", "PIXIV:42", "Post")
        runCurrent()
        assertEquals(1, requests)
        assertEquals(1L, delegate.observeStatistics().first().codexStatistics.getValue("codex")
            .posts.getValue("PIXIV:42").opens)
        repository.addCodexUsage(CodexUsageTarget("codex", "PIXIV:42"), 300L)
        repository.observeStatistics().first()
        runCurrent()
        assertEquals(2, requests)
        assertTrue(repository.observeStatistics().first().thumbnails.isEmpty())
        offline = false
        repository.recordPostAvailable(post)
        runCurrent()
        assertEquals(3, requests)
        assertTrue(repository.observeStatistics().first().thumbnails.containsKey("PIXIV:42"))
        repository.observeStatistics().first()
        runCurrent()
        assertEquals(3, requests)
    }

    @Test fun `statistics updates outside Codex rankings skip the thumbnail directory pass`() = runTest {
        val delegate = InMemoryStatisticsRepository()
        val directory = temporary.newFolder()
        val repository = ThumbnailStatisticsRepository(delegate, backgroundScope,
            StatisticsThumbnailStore(directory)) { _, _ -> false }
        repository.recordCodexPostOpen("codex", "PIXIV:42", "Post")
        runCurrent()
        // Only a reconcile pass would delete this unowned file.
        val marker = directory.resolve("unowned").apply { writeText("x") }

        repository.recordWatchedPost(SourceKey.PIXIV, setOf("tag"))
        repository.recordAppOpen()
        runCurrent()
        assertTrue(marker.exists())

        repository.recordCodexPostOpen("codex", "PIXIV:7", "Other")
        runCurrent()
        assertFalse(marker.exists())
    }

    private fun post(id: String) = Post(PostId(SourceKey.PIXIV, id), ImageRef("https://example.test/$id", null, null),
        null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(),
        rawTags = emptyList(), authorName = null, createdAtEpochMs = null)
}
