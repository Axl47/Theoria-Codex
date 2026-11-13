package com.theoriacodex.app.source

import com.theoriacodex.app.post.HASH
import com.theoriacodex.app.post.filePost
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCopyLookupTest {
    private val seed = filePost(SourceKey.DANBOORU, "1", HASH)
    private val gelbooruCopy = filePost(SourceKey.GELBOORU, "9", HASH)

    @Test
    fun `only the other available sites are asked and every copy found comes back`() = runTest {
        val asked = mutableListOf<SourceKey>()
        val rule34Copy = filePost(SourceKey.RULE34XXX, "5", HASH)
        val registry = registry(
            SourceKey.DANBOORU to { _ -> seed },
            SourceKey.GELBOORU to { md5 -> gelbooruCopy.takeIf { md5 == HASH } },
            SourceKey.RULE34XXX to { _ -> rule34Copy },
            unavailable = SourceKey.IWARA to { _ -> testPost(SourceKey.IWARA, "7") },
            onAsk = asked::add,
        )

        val found = FileCopyLookup(registry).find(seed)

        assertEquals(setOf(gelbooruCopy, rule34Copy), found.toSet())
        assertEquals(setOf(SourceKey.GELBOORU, SourceKey.RULE34XXX), asked.toSet())
    }

    @Test
    fun `a site that fails or stalls does not hide another site's copy`() = runTest {
        val registry = registry(
            SourceKey.GELBOORU to { _ -> gelbooruCopy },
            SourceKey.RULE34XXX to { _ -> throw IOException("offline") },
            SourceKey.AIBOORU to { _ -> awaitCancellation() },
        )

        assertEquals(listOf(gelbooruCopy), FileCopyLookup(registry, timeoutMs = 1_000).find(seed))
    }

    @Test
    fun `nothing found is an empty list`() = runTest {
        val registry = registry(SourceKey.GELBOORU to { _ -> null })

        assertEquals(emptyList<Post>(), FileCopyLookup(registry).find(seed))
    }

    @Test
    fun `the action is offered only with a file hash and another site that can look it up`() {
        val lookup = FileCopyLookup(registry(SourceKey.DANBOORU to { _ -> null }, SourceKey.GELBOORU to { _ -> null }))

        assertTrue(lookup.canFind(seed))
        assertFalse(lookup.canFind(testPost(SourceKey.DANBOORU, "2")))
        assertFalse(FileCopyLookup(registry(SourceKey.DANBOORU to { _ -> null })).canFind(seed))
    }

    /** A registry whose available sources answer file-hash lookups with [answers]; [unavailable] is registered but off. */
    private fun registry(
        vararg answers: Pair<SourceKey, suspend (String) -> Post?>,
        unavailable: Pair<SourceKey, suspend (String) -> Post?>? = null,
        onAsk: (SourceKey) -> Unit = {},
    ): SourceAdapterRegistry {
        val adapters = (answers.toList() + listOfNotNull(unavailable)).associate { (source, answer) ->
            source to LookupAdapter(source) { md5 -> onAsk(source); answer(md5) }
        }
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = answers.map { it.first }.toSet()
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(adapters)
        }
    }
}

/** A source whose only working call is the file-hash lookup. */
private class LookupAdapter(
    override val sourceKey: SourceKey,
    private val answer: suspend (String) -> Post?,
) : SourceAdapter by unusedAdapter, FileHashLookupSourceAdapter {
    override suspend fun findByFileHash(md5: String): Post? = answer(md5)
}

private val unusedAdapter: SourceAdapter = java.lang.reflect.Proxy.newProxyInstance(
    SourceAdapter::class.java.classLoader,
    arrayOf(SourceAdapter::class.java),
) { _, method, _ -> error("${method.name} is not used by FileCopyLookup") } as SourceAdapter
