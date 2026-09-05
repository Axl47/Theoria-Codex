package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.CodexSummary
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** SQL selects only bounded covers; decoding never loads the rest of a collection. */
internal class RoomCodexSummaryQueries(
    private val dao: CodexLikesDao,
    private val codec: LocalPostPayloadCodec,
) {
    fun observeSummaries(codexIds: Set<String>, coverLimit: Int): Flow<List<CodexSummary>> {
        if (codexIds.isEmpty()) return flowOf(emptyList())
        return dao.observeCodexSummaries(codexIds.sorted(), coverLimit.coerceAtLeast(0))
            .map { rows ->
                rows.groupBy { it.codexId }.map { (codexId, covers) ->
                    CodexSummary(
                        codexId = codexId,
                        itemCount = covers.first().itemCount,
                        coverPosts = covers.mapNotNull { row ->
                            val payload = row.payloadJson ?: return@mapNotNull null
                            codec.decode(PostEntity(requireNotNull(row.source), requireNotNull(row.sourcePostId), payload))
                        },
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    fun observeSavedPostIds(codexIds: Set<String>): Flow<Set<PostId>> {
        if (codexIds.isEmpty()) return flowOf(emptySet())
        return dao.observeSavedPostIds(codexIds.sorted()).map { rows ->
            rows.mapNotNullTo(linkedSetOf()) { row ->
                runCatching { SourceKey.valueOf(row.source) }.getOrNull()?.let { source ->
                    PostId(source, row.sourcePostId)
                }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }
}
