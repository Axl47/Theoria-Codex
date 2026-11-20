package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecentSearchSection
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DEFAULT_RECENT_WATCHED_LIMIT = 200
const val DEFAULT_RECENT_SEARCH_LIMIT = 100

class RoomRecentsRepository(
    private val database: TheoriaRoomDatabase,
    private val watchedLimit: Int = DEFAULT_RECENT_WATCHED_LIMIT,
    private val searchLimit: Int = DEFAULT_RECENT_SEARCH_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentsRepository {
    private val dao = database.recentsDao()
    private val contentDao = database.codexLikesDao()
    private val sharedPosts = SharedPosts(contentDao)
    private val writer = RecentsWriter(dao, sharedPosts)

    /** Every watched collector projects the same bounded query, so one retained decode serves them all. */
    private val watchedPayloads = RetainedPostPayloads(LocalPostPayloadCodec::decode)

    init {
        require(watchedLimit >= 0 && searchLimit >= 0)
    }

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> = dao.observeWatched().map { rows ->
        val posts = watchedPayloads.project(rows.map { PostEntity(it.source, it.sourcePostId, it.payloadJson) })
        rows.zip(posts) { row, post ->
            val origin = runCatching { ViewerStreamSource.valueOf(row.origin) }
                .getOrElse { error("Invalid recent watched origin ${row.origin}") }
            val section = runCatching { RecentPostSection.valueOf(row.section) }
                .getOrElse { error("Invalid recent watched section ${row.section}") }
            RecentPostEntry(
                post = post,
                viewedAtEpochMs = row.viewedAtEpochMs,
                origin = origin,
                originQueryHash = row.originQueryHash,
                section = section,
                maxViewedMediaNumber = row.maxViewedMediaNumber,
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun observeWatchedPostIds(): Flow<Set<PostId>> = dao.observeWatchedPostIds().map { rows ->
        rows.mapTo(linkedSetOf()) { PostId(SourceKey.parseOrThrow(it.source), it.sourcePostId) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun observeSearches(): Flow<List<RecentSearchEntry>> = dao.observeSearches()
        .map { rows -> rows.map(RecentSearchEntity::toDomain) }.flowOn(Dispatchers.Default)

    override suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
        viewedMediaNumber: Int,
    ) {
        database.withTransaction {
            val recordedAt = clock()
            val previous = dao.watched(post.id.source.id, post.id.sourcePostId, section.name)
            val preserve = origin == ViewerStreamSource.RECENTS && previous != null
            sharedPosts.upsert(post)
            writer.upsertWatched(
                post.id,
                section.name,
                recordedAt,
                if (preserve) previous.origin else origin.name,
                if (preserve) previous.originQueryHash else originQueryHash,
                maxOf(previous?.maxViewedMediaNumber ?: 1, viewedMediaNumber.coerceAtLeast(1)),
            )
            writer.trimWatched(watchedLimit)
        }
    }

    override suspend fun recordWatchedMediaProgress(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
        viewedMediaNumber: Int,
    ) {
        database.withTransaction {
            val normalizedMediaNumber = viewedMediaNumber.coerceAtLeast(1)
            val raised = dao.raiseWatchedMediaProgress(
                post.id.source.id,
                post.id.sourcePostId,
                section.name,
                normalizedMediaNumber,
            )
            if (raised == 0 && dao.watched(post.id.source.id, post.id.sourcePostId, section.name) == null) {
                sharedPosts.upsert(post)
                writer.upsertWatched(post.id, section.name, clock(), origin.name, originQueryHash, normalizedMediaNumber)
                writer.trimWatched(watchedLimit)
            }
        }
    }

    override suspend fun recordSearch(
        query: Query,
        queryHash: String,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
        sourceTags: Map<SourceKey, List<String>>,
    ) {
        val normalized = queryHash.trim()
        if (normalized.isBlank()) return
        database.withTransaction {
            writer.upsertSearch(normalized, RecentSearchPayloadCodec.encodeJson(query, kind, sources, sourceTags), clock())
            writer.trimSearches(searchLimit)
        }
    }

    override suspend fun restoreEntries(
        watchedPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
    ) {
        database.withTransaction {
            watchedPosts.asReversed().forEach { entry ->
                val post = entry.post
                val existing = dao.watched(post.id.source.id, post.id.sourcePostId, entry.section.name)
                if (existing == null || entry.viewedAtEpochMs > existing.viewedAtEpochMs) {
                    sharedPosts.upsert(post)
                    writer.upsertWatched(
                        post.id,
                        entry.section.name,
                        entry.viewedAtEpochMs,
                        entry.origin.name,
                        entry.originQueryHash,
                        entry.maxViewedMediaNumber.coerceAtLeast(1),
                    )
                }
            }
            searches.asReversed().forEach { entry ->
                val queryHash = entry.queryHash.trim()
                val existing = queryHash.takeIf(String::isNotBlank)?.let(dao::search)
                if (
                    queryHash.isNotBlank() &&
                    (existing == null || entry.searchedAtEpochMs > existing.searchedAtEpochMs)
                ) {
                    writer.upsertSearch(queryHash, RecentSearchPayloadCodec.encodeJson(entry), entry.searchedAtEpochMs)
                }
            }
            writer.trimWatched(watchedLimit)
            writer.trimSearches(searchLimit)
        }
    }

    override suspend fun clearWatchedPosts(section: RecentPostSection) {
        database.withTransaction {
            val removed = dao.watched().filter { it.section == section.name }
            dao.deleteWatchedSection(section.name)
            cleanupWatchedPostsInside(removed)
        }
    }

    override suspend fun clearSearches(section: RecentSearchSection): List<RecentSearchEntry> =
        database.withTransaction {
            val removed = dao.searches().map(RecentSearchEntity::toDomain).filter(section::contains)
            dao.deleteSearchesByHashes(removed.map { it.queryHash })
            removed
        }

    override suspend fun clearAll() {
        database.withTransaction {
            val removed = dao.watched()
            dao.deleteWatched()
            dao.deleteSearches()
            cleanupWatchedPostsInside(removed)
        }
    }

    private fun cleanupWatchedPostsInside(removed: List<RecentWatchedEntity>) {
        sharedPosts.releaseIfOrphaned(removed.map { it.source to it.sourcePostId })
    }
}
