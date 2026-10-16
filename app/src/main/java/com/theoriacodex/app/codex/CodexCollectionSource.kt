package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
