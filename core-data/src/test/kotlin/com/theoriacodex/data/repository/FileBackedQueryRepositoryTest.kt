package com.theoriacodex.data.repository

import com.theoriacodex.data.testing.RecordingIoDispatcher
import com.theoriacodex.data.testing.ControllableIoDispatcher
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class FileBackedQueryRepositoryTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `query repository persists applied query without scroll state`() = runTest {
        val dir = tempDir("query-store-")
        val first = FileBackedQueryRepository(dir)
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTerms = listOf(
                SearchTerm(value = "landscape"),
                SearchTerm(
                    value = "najar",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
            ),
            excludeTerms = listOf(
                SearchTerm(
                    value = "english",
                    facet = SearchFacet.LANGUAGE,
                    sourceNamespace = "language",
                ),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = 10,
        )
        first.upsertAppliedQuery("source:PIXIV", query)

        val second = FileBackedQueryRepository(dir)
        val loaded = second.observeAppliedQuery("source:PIXIV").first()
        val storedJson = dir.resolve("query_store.json").readText()

        assertEquals(query, loaded)
        assertTrue(storedJson.contains("\"includeTerms\""))
        assertTrue(storedJson.contains("\"excludeTerms\""))
        assertTrue(storedJson.contains("\"includeTags\""))
        assertTrue(storedJson.contains("\"excludeTags\""))
        assertFalse(storedJson.contains("scrollOffsets"))
    }

    @Test
    fun `query publishes verified recovery through the shared registry`() = runTest {
        val dir = tempDir("legacy-json-recovery-registry-")
        val queryBytes = "{broken-query".toByteArray()
        dir.resolve("query_store.json").writeBytes(queryBytes)
        val registry = LegacyJsonRecoveryRegistry()

        FileBackedQueryRepository(dir, recoveryRegistry = registry)

        val recoveries = registry.recoveries.value
        assertEquals(setOf("Saved searches"), recoveries.map { it.logicalStore }.toSet())
        recoveries.forEach { recovery ->
            assertEquals(queryBytes.size.toLong(), recovery.byteCount)
            assertTrue(File(recovery.backupPath!!).readBytes().contentEquals(queryBytes))
        }
    }

    @Test
    fun `verified query quarantine is rediscovered after process restart`() = runTest {
        val dir = tempDir("legacy-json-recovery-restart-")
        val original = "{broken-query".toByteArray()
        dir.resolve("query_store.json").writeBytes(original)
        val firstProcess = LegacyJsonRecoveryRegistry()
        FileBackedQueryRepository(dir, recoveryRegistry = firstProcess)
        val quarantine = File(firstProcess.recoveries.value.single().backupPath!!)

        val restartedProcess = LegacyJsonRecoveryRegistry()
        FileBackedQueryRepository(dir, recoveryRegistry = restartedProcess)

        val rediscovered = restartedProcess.recoveries.value.single()
        assertEquals("Saved searches", rediscovered.logicalStore)
        assertEquals(quarantine.absolutePath, rediscovered.backupPath)
        assertEquals(original.size.toLong(), rediscovered.byteCount)
        assertTrue(quarantine.readBytes().contentEquals(original))
    }

    @Test
    fun `file-backed repository initialization and writes use the injected IO lane`() = runTest {
        val dir = tempDir("query-injected-io-")
        RecordingIoDispatcher("repository-file-io").use { dispatcher ->
            val repository = FileBackedQueryRepository(dir, ioDispatcher = dispatcher)
            val dispatchesAfterInitialization = dispatcher.executionThreadNames.size

            repository.upsertAppliedQuery("query", sampleQuery())

            assertTrue(dispatchesAfterInitialization > 0)
            assertTrue(dispatcher.executionThreadNames.size > dispatchesAfterInitialization)
            assertTrue(dispatcher.executionThreadNames.all { name -> name == "repository-file-io" })
            assertEquals(sampleQuery(), FileBackedQueryRepository(dir).observeAppliedQuery("query").first())
        }
    }

    @Test
    fun `file-backed repository serializes concurrent mutations before persistence`() = runTest {
        val dir = tempDir("query-concurrent-writes-")
        val repository = FileBackedQueryRepository(dir)

        coroutineScope {
            repeat(24) { index ->
                launch(Dispatchers.Default) {
                    repository.upsertAppliedQuery("query-$index", sampleQuery(listOf("tag-$index")))
                }
            }
        }

        val reconstructed = FileBackedQueryRepository(dir)
        repeat(24) { index ->
            assertEquals(
                listOf("tag-$index"),
                reconstructed.observeAppliedQuery("query-$index").first()?.includeTags,
            )
        }
    }

    @Test
    fun `cancelled Codex persistence rolls back every in-memory flow`() = runTest {
        val dir = tempDir("codex-cancelled-persistence-")
        ControllableIoDispatcher("codex-io").use { dispatcher ->
            val repository = FileBackedCodexRepository(dir, ioDispatcher = dispatcher)
            val codex = repository.createCodex("Saved")
            val persisted = samplePost("persisted", localPath = null)
            repository.addItem(codex.codexId, persisted)
            val originalItems = repository.observeCodexItems(codex.codexId).first()
            val attempted = samplePost("attempted", localPath = null, source = SourceKey.GELBOORU)
            val cancellation = CancellationException("cancelled write")
            dispatcher.dispatchFailure = cancellation

            val failure = runCatching { repository.addItem(codex.codexId, attempted) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(cancellation.message, failure?.message)
            assertEquals(originalItems, repository.observeCodexItems(codex.codexId).first())
            assertEquals(null, repository.getPost(attempted.id))
            assertEquals(listOf(persisted), repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first())
            val reconstructed = FileBackedCodexRepository(dir)
            assertEquals(originalItems, reconstructed.observeCodexItems(codex.codexId).first())
            assertEquals(null, reconstructed.getPost(attempted.id))
        }
    }

    @Test
    fun `failed UI restore persistence rolls back plain and mapped state`() = runTest {
        val dir = tempDir("ui-restore-failed-persistence-")
        ControllableIoDispatcher("ui-restore-io").use { dispatcher ->
            val repository = FileBackedUiRestoreRepository(dir, ioDispatcher = dispatcher)
            val originalScroll = SearchScrollState(firstVisibleItemIndex = 2, firstVisibleItemOffsetPx = 30)
            repository.setLastTab("codex")
            repository.setSearchScrollState("query", originalScroll)
            dispatcher.dispatchFailure = RejectedExecutionException("write rejected")

            val tabFailure = runCatching { repository.setLastTab("settings") }.exceptionOrNull()
            val scrollFailure = runCatching {
                repository.setSearchScrollState(
                    "query",
                    SearchScrollState(firstVisibleItemIndex = 9, firstVisibleItemOffsetPx = 90),
                )
            }.exceptionOrNull()

            assertTrue(tabFailure is RejectedExecutionException)
            assertTrue(scrollFailure is RejectedExecutionException)
            assertEquals("codex", repository.getLastTab())
            assertEquals(originalScroll, repository.getSearchScrollState("query"))
            val reconstructed = FileBackedUiRestoreRepository(dir)
            assertEquals("codex", reconstructed.getLastTab())
            assertEquals(originalScroll, reconstructed.getSearchScrollState("query"))
        }
    }

    @Test
    fun `query repository falls back when persisted source or sort is unknown`() = runTest {
        val dir = tempDir("query-unknown-enums-")
        dir.resolve("query_store.json").writeText(
            """
            {
              "queries": {
                "source:REMOVED_SOURCE": {
                  "modeType": "source",
                  "modeSource": "REMOVED_SOURCE",
                  "includeTags": ["landscape"],
                  "excludeTags": ["comic"],
                  "sort": "REMOVED_SORT",
                  "dateFromEpochMs": 100,
                  "dateToEpochMs": 200,
                  "minScore": 10
                }
              },
              "scrollOffsets": {
                "qhash": 320
              }
            }
            """.trimIndent(),
        )

        val repository = FileBackedQueryRepository(dir)
        val loaded = repository.observeAppliedQuery("source:REMOVED_SOURCE").first()

        assertEquals(QueryMode.Unified, loaded?.mode)
        assertEquals(SortMode.TOP, loaded?.sort)
        assertEquals(listOf("landscape"), loaded?.includeTags)
        assertEquals(listOf(SearchTerm(value = "landscape")), loaded?.includeTerms)
        assertTrue(dir.resolve("query_store.json").readText().contains("scrollOffsets"))
    }

    @Test
    fun `query repository distinguishes absent typed terms from explicit empty terms`() = runTest {
        val dir = tempDir("query-null-vs-empty-terms-")
        dir.resolve("query_store.json").writeText(
            """
            {
              "queries": {
                "legacy": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["legacy include"],
                  "excludeTags": ["legacy exclude"],
                  "includeTerms": null,
                  "excludeTerms": null,
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                },
                "typed-empty": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["must not return"],
                  "excludeTags": ["must not return"],
                  "includeTerms": [],
                  "excludeTerms": [],
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                },
                "typed-malformed": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["must not return"],
                  "excludeTags": [],
                  "includeTerms": [
                    null,
                    {"value": "", "facet": "TAG"},
                    {"value": "missing"},
                    {"value": "blank", "facet": " "},
                    {"value": "unknown", "facet": "REMOVED_FACET"},
                    {"value": " najar ", "facet": "ARTIST", "sourceNamespace": " artist "}
                  ],
                  "excludeTerms": [null],
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                }
              },
              "scrollOffsets": {}
            }
            """.trimIndent(),
        )

        val repository = FileBackedQueryRepository(dir)
        val legacy = repository.observeAppliedQuery("legacy").first()
        val typedEmpty = repository.observeAppliedQuery("typed-empty").first()
        val typedMalformed = repository.observeAppliedQuery("typed-malformed").first()

        assertEquals(listOf(SearchTerm(value = "legacy include")), legacy?.includeTerms)
        assertEquals(listOf(SearchTerm(value = "legacy exclude")), legacy?.excludeTerms)
        assertEquals(emptyList<SearchTerm>(), typedEmpty?.includeTerms)
        assertEquals(emptyList<SearchTerm>(), typedEmpty?.excludeTerms)
        assertEquals(
            listOf(
                SearchTerm(value = "missing"),
                SearchTerm(value = "blank"),
                SearchTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist"),
            ),
            typedMalformed?.includeTerms,
        )
        assertEquals(emptyList<SearchTerm>(), typedMalformed?.excludeTerms)
    }

}
