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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailStatisticsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

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
        val post = Post(PostId(SourceKey.PIXIV, "42"), ImageRef("https://example.test/42", null, null),
            null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(),
            rawTags = emptyList(), authorName = null, createdAtEpochMs = null)
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
}
