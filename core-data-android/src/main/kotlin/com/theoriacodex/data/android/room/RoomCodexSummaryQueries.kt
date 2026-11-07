package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.CodexSummary
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** SQL selects only bounded covers; decoding never loads the rest of a collection. */
internal class RoomCodexSummaryQueries(
    private val dao: CodexLikesDao,
    private val payloads: RetainedPostPayloadCache,
) {
    /** Covers keep their decoded Posts across updates; only changed cover payloads decode again. */
    fun observeSummaries(codexIds: Set<String>, coverLimit: Int): Flow<List<CodexSummary>> {
        if (codexIds.isEmpty()) return flowOf(emptyList())
        val ids = codexIds.sorted()
        val limit = coverLimit.coerceAtLeast(0)
        return flow {
            val projection = payloads.projection("summaries:$limit:${ids.joinToString("\u0000")}")
            dao.observeCodexSummaries(ids, limit).collect { rows ->
                val covers = rows.filter { it.payloadJson != null }
                val posts = projection.project(covers.map { row ->
                    PostEntity(requireNotNull(row.source), requireNotNull(row.sourcePostId), requireNotNull(row.payloadJson))
                })
                payloads.trim()
                val postByRow = IdentityHashMap<CodexSummaryRow, Post>(covers.size)
                covers.zip(posts, postByRow::put)
                emit(rows.groupBy { it.codexId }.map { (codexId, group) ->
                    CodexSummary(
                        codexId = codexId,
                        itemCount = group.first().itemCount,
                        coverPosts = group.mapNotNull(postByRow::get),
                    )
                })
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
