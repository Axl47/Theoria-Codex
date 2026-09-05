package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexCollectionSourceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `overview never subscribes to full posts and only selected action sheet hydrates tags`() = runTest {
        val stored = InMemoryCodexRepository()
        stored.ensureCodex("active", "Active")
        stored.ensureCodex("other", "Other")
        stored.addItems("active", (1..20).map { testPost(sourcePostId = "$it") })
        val subscribed = mutableListOf<String>()
        val repository = object : CodexRepository by stored {
            override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
                subscribed += codexId
                return stored.observeCodexPosts(codexId, sort)
            }
        }
        val source = CodexCollectionSource(repository, folder.root)

        val overview = source.observe(setOf("active")).first()
        assertEquals(mapOf("active" to 20), overview.itemCounts)
        assertEquals(emptyList<String>(), subscribed)
        source.observeActionOptions("active", setOf(SourceKey.PIXIV)).first()
        assertEquals(listOf("active"), subscribed)
    }
}
