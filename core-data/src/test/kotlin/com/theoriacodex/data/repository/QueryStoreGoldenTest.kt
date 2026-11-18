package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * query_store.json files older builds wrote must load to the same searches. gson-written.json is the Gson store's
 * own output (HTML-escaped strings); legacy.json is a v1 entry and a damaged v2 entry beside a retired key.
 */
internal class QueryStoreGoldenTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `a Gson-written store loads every saved search`() = runTest {
        val repository = repositoryWith(golden("gson-written.json"))

        assertEquals(gsonWrittenSearch, repository.observeAppliedQuery("search").first())
        assertEquals(emptyQuery(), repository.observeAppliedQuery("feed").first())
    }

    @Test
    fun `legacy and damaged entries repair the way Gson read them`() = runTest {
        val recoveries = FileStoreRecoveryRegistry()
        val repository = repositoryWith(golden("legacy.json"), recoveries)

        assertEquals(
            Query(
                mode = QueryMode.Source(SourceKey.PIXIV),
                includeTags = listOf("a & b"),
                excludeTags = listOf("c"),
                sort = SortMode.TOP,
                dateRange = null,
                minScore = 5,
            ),
            repository.observeAppliedQuery("old").first(),
        )
        assertEquals(
            emptyQuery().copy(includeTermGroups = listOf(SearchTermGroup.single(SearchTerm("z", SearchFacet.ARTIST)))),
            repository.observeAppliedQuery("grouped").first(),
        )
        assertTrue(recoveries.recoveries.value.isEmpty())
    }

    @Test
    fun `the Gson-written store holds every stored key`() {
        val queries = mapOf("search" to gsonWrittenSearch, "feed" to emptyQuery())
        assertStoredFormat(golden("gson-written.json") to QueryStoreFile(queries.mapValues { QueryStorageCodec.encode(it.value) }))
    }

    private fun repositoryWith(json: String, recoveries: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry()) =
        tempDir("query-golden-").let { dir ->
            File(dir, "query_store.json").writeText(json)
            FileBackedQueryRepository(dir, recoveryRegistry = recoveries)
        }

    private val gsonWrittenSearch = run {
        val artist = SearchTerm("é☃", SearchFacet.ARTIST, "artist")
        val escaped = listOf(SearchTerm("tom & jerry's <cut>"), SearchTerm("a=b"))
        Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTerms = escaped + artist,
            excludeTerms = listOf(SearchTerm("english", SearchFacet.LANGUAGE, "language")),
            sort = SortMode.NEWEST,
            dateRange = DateRange(1_700_000_000_000L, 1_790_000_000_000L),
            minScore = 10,
            includeTermGroups = listOf(SearchTermGroup(escaped), SearchTermGroup.single(artist)),
        )
    }

    private fun emptyQuery() = Query(
        mode = QueryMode.Unified,
        includeTerms = emptyList(),
        excludeTerms = emptyList(),
        sort = SortMode.TOP,
        dateRange = null,
        minScore = null,
    )

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/query-store/$name")) { "missing golden $name" }.readText()
}
