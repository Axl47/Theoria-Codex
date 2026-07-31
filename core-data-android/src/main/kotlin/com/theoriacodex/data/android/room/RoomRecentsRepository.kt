package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

const val DEFAULT_RECENT_WATCHED_LIMIT = 200
const val DEFAULT_RECENT_SEARCH_LIMIT = 100

class RoomRecentsRepository(
    private val database: TheoriaRoomDatabase,
    private val watchedLimit: Int = DEFAULT_RECENT_WATCHED_LIMIT,
    private val searchLimit: Int = DEFAULT_RECENT_SEARCH_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    gson: Gson = Gson(),
) : RecentsRepository {
    private val dao = database.recentsDao()
    private val contentDao = database.codexLikesDao()
    private val postCodec = LocalPostPayloadCodec(gson)
    private val queryGson = gson

    init {
        require(watchedLimit >= 0 && searchLimit >= 0)
    }

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> = dao.observeWatched().map { rows ->
        rows.map { row ->
            val post = postCodec.decode(PostEntity(row.source, row.sourcePostId, row.payloadJson))
            val origin = runCatching { ViewerStreamSource.valueOf(row.origin) }
                .getOrElse { error("Invalid recent watched origin ${row.origin}") }
            RecentPostEntry(post, row.viewedAtEpochMs, origin, row.originQueryHash)
        }
    }

    override fun observeSearches(): Flow<List<RecentSearchEntry>> = dao.observeSearches().map { rows ->
        rows.map { row ->
            RecentSearchEntry(
                query = QueryStorageCodec.decodeJson(row.queryPayloadJson, queryGson),
                queryHash = row.queryHash,
                searchedAtEpochMs = row.searchedAtEpochMs,
            )
        }
    }

    override fun observeActivity(): Flow<List<RecentActivityEntry>> =
        combine(observeWatchedPosts(), observeSearches()) { watched, searches ->
            buildList {
                watched.forEach { add(RecentActivityEntry.Watched(it)) }
                searches.forEach { add(RecentActivityEntry.Search(it)) }
            }.sortedWith(
                compareByDescending<RecentActivityEntry> { it.occurredAtEpochMs }
                    .thenBy { if (it is RecentActivityEntry.Watched) 0 else 1 }
            )
        }

    override suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
    ) {
        database.withTransaction {
            val previous = dao.watched(post.id.source.name, post.id.sourcePostId)
            val preserve = origin == ViewerStreamSource.RECENTS && previous != null
            upsertPost(post)
            dao.upsertWatched(
                RecentWatchedEntity(
                    post.id.source.name,
                    post.id.sourcePostId,
                    clock(),
                    dao.nextWatchedSequence(),
                    if (preserve) previous.origin else origin.name,
                    if (preserve) previous.originQueryHash else originQueryHash,
                )
            )
            dao.trimWatched(watchedLimit)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun recordSearch(query: com.theoriacodex.domain.model.Query, queryHash: String) {
        val normalized = queryHash.trim()
        if (normalized.isBlank()) return
        database.withTransaction {
            dao.upsertSearch(
                RecentSearchEntity(
                    normalized,
                    QueryStorageCodec.encodeJson(query, queryGson),
                    clock(),
                    dao.nextSearchSequence(),
                )
            )
            dao.trimSearches(searchLimit)
        }
    }

    override suspend fun clearWatchedPosts() {
        database.withTransaction {
            dao.deleteWatched()
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearWatchedPosts(origin: ViewerStreamSource) {
        database.withTransaction {
            dao.deleteWatchedOrigin(origin.name)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearWatchedPostsExcept(origin: ViewerStreamSource) {
        database.withTransaction {
            dao.deleteWatchedExceptOrigin(origin.name)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearSearches() {
        database.withTransaction { dao.deleteSearches() }
    }

    override suspend fun clearAll() {
        database.withTransaction {
            dao.deleteWatched()
            dao.deleteSearches()
            contentDao.deleteOrphanPosts()
        }
    }

    private fun upsertPost(post: Post) {
        val entity = PostEntity(post.id.source.name, post.id.sourcePostId, postCodec.encode(post))
        if (contentDao.insertPost(entity) == -1L) {
            contentDao.updatePost(entity.source, entity.sourcePostId, entity.payloadJson)
        }
    }
}
