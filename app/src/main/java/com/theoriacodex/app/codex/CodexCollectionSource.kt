package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch

internal data class CodexCollectionPresentation(
    val itemCounts: Map<String, Int> = emptyMap(),
    val coverCandidates: Map<String, List<CodexCoverCandidate>> = emptyMap(),
)

internal data class CodexActionOptions(
    val sources: List<CodexSearchSourceOption> = emptyList(),
    val tags: Map<SourceKey, List<CodexSearchTagOption>> = emptyMap(),
)

/** Summary queries decode bounded covers; full tag projections exist only for an open sheet. */
internal class CodexCollectionSource(
    private val repository: CodexRepository,
    private val storageDirectory: File,
    private val cacheRepository: CacheRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Only the open save sheet observes full membership tags; scoring stays off Main. */
    fun observeSaveRelevance(
        codexIds: Set<String>, posts: List<Post>, searchQuery: Query? = null,
    ): Flow<Map<String, Double>> {
        if (codexIds.isEmpty()) return flowOf(emptyMap())
        val relevance = CodexSaveRelevance(posts, searchQuery)
        if (!relevance.hasSignals) return flowOf(emptyMap())
        return combine(codexIds.sorted().map { id ->
            repository.observeCodexPosts(id, CodexSortMode.NEWEST_SAVED)
                .map { saved -> id to relevance.score(saved) }
                .catch { emit(id to 0.0) }
        }) { scores -> scores.toMap() }.flowOn(Dispatchers.Default)
    }

    fun observe(codexIds: Set<String>): Flow<CodexCollectionPresentation> =
        combine(repository.observeCodexSummaries(codexIds), cacheRepository.observeNamedThumbnailRevisions()) { summaries, revision ->
            val thumbnails = storageDirectory.resolve("cache/thumbnails").listFiles().orEmpty().toList()
            CodexCollectionPresentation(
                itemCounts = summaries.associate { it.codexId to it.itemCount },
                coverCandidates = summaries.associate { summary ->
                    summary.codexId to resolveCodexCoverCandidates(storageDirectory, summary.coverPosts, thumbnails)
                } + listOf(FOLLOWED_CODEX_ID, SAVED_SEARCHES_CODEX_ID).associateWith {
                    resolveNamedCodexCoverCandidates(storageDirectory, it, revision)
                },
            )
        }.flowOn(dispatcher)

    fun observeActionOptions(codexId: String, availableSources: Set<SourceKey>): Flow<CodexActionOptions> =
        repository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).map { posts ->
            val sources = codexSearchSourceOptions(posts, availableSources)
            CodexActionOptions(sources, sources.associate { it.source to codexSearchTagOptions(posts, it.source) })
        }.flowOn(Dispatchers.Default)
}
