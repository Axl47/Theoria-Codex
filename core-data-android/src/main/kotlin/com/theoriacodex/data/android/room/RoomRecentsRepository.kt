package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
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
    private val sharedPostPayloads = SharedPostPayloadWriter(contentDao, postCodec)
    private val queryGson = gson

    init {
        require(watchedLimit >= 0 && searchLimit >= 0)
    }

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> = dao.observeWatched().map { rows ->
        rows.map { row ->
            val post = postCodec.decode(PostEntity(row.source, row.sourcePostId, row.payloadJson))
            val origin = runCatching { ViewerStreamSource.valueOf(row.origin) }
                .getOrElse { error("Invalid recent watched origin ${row.origin}") }
            val section = runCatching { RecentPostSection.valueOf(row.section) }
                .getOrElse { error("Invalid recent watched section ${row.section}") }
            RecentPostEntry(post, row.viewedAtEpochMs, origin, row.originQueryHash, section)
        }
    }

    override fun observeSearches(): Flow<List<RecentSearchEntry>> = dao.observeSearches().map { rows ->
        rows.map { row ->
            val payload = RecentSearchPayloadCodec.decodeJson(row.queryPayloadJson, queryGson)
            RecentSearchEntry(
                query = payload.query,
                queryHash = row.queryHash,
                searchedAtEpochMs = row.searchedAtEpochMs,
                kind = payload.kind,
                sources = payload.sources,
            )
        }
    }

    override fun observeActivity(): Flow<List<RecentActivityEntry>> =
        combine(observeWatchedPosts(), observeSearches()) { watched, searches ->
            buildList<RecentActivityEntry> {
                watched
                    .filterNot { entry -> entry.section == RecentPostSection.FYP }
                    .distinctBy { entry -> entry.post.id }
                    .forEach { add(RecentActivityEntry.Watched(it)) }
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
        section: RecentPostSection,
    ) {
        database.withTransaction {
            val recordedAt = clock()
            val previous = dao.watched(post.id.source.name, post.id.sourcePostId, section.name)
            val preserve = origin == ViewerStreamSource.RECENTS && previous != null
            sharedPostPayloads.upsert(post)
            dao.upsertWatched(
                RecentWatchedEntity(
                    post.id.source.name,
                    post.id.sourcePostId,
                    section.name,
                    recordedAt,
                    dao.nextWatchedSequence(),
                    if (preserve) previous.origin else origin.name,
                    if (preserve) previous.originQueryHash else originQueryHash,
                )
            )
            dao.trimWatched(watchedLimit)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun recordSearch(
        query: Query,
        queryHash: String,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
    ) {
        val normalized = queryHash.trim()
        if (normalized.isBlank()) return
        database.withTransaction {
            dao.upsertSearch(
                RecentSearchEntity(
                    normalized,
                    RecentSearchPayloadCodec.encodeJson(query, kind, sources, queryGson),
                    clock(),
                    dao.nextSearchSequence(),
                )
            )
            dao.trimSearches(searchLimit)
        }
    }

    override suspend fun restoreEntries(
        watchedPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
    ) {
        database.withTransaction {
            watchedPosts.asReversed().forEach { entry ->
                val post = entry.post
                val existing = dao.watched(post.id.source.name, post.id.sourcePostId, entry.section.name)
                if (existing == null || entry.viewedAtEpochMs > existing.viewedAtEpochMs) {
                    sharedPostPayloads.upsert(post)
                    dao.upsertWatched(
                        RecentWatchedEntity(
                            post.id.source.name,
                            post.id.sourcePostId,
                            entry.section.name,
                            entry.viewedAtEpochMs,
                            dao.nextWatchedSequence(),
                            entry.origin.name,
                            entry.originQueryHash,
                        )
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
                    dao.upsertSearch(
                        RecentSearchEntity(
                            queryHash,
                            RecentSearchPayloadCodec.encodeJson(entry, queryGson),
                            entry.searchedAtEpochMs,
                            dao.nextSearchSequence(),
                        )
                    )
                }
            }
            dao.trimWatched(watchedLimit)
            dao.trimSearches(searchLimit)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearWatchedPosts() {
        database.withTransaction {
            dao.deleteWatched()
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearWatchedPosts(section: RecentPostSection) {
        database.withTransaction {
            dao.deleteWatchedSection(section.name)
            contentDao.deleteOrphanPosts()
        }
    }

    override suspend fun clearSearches(queryHashPrefix: String?) {
        database.withTransaction {
            if (queryHashPrefix == null) dao.deleteSearches() else dao.deleteSearchesWithPrefix(queryHashPrefix)
        }
    }

    override suspend fun clearAll() {
        database.withTransaction {
            dao.deleteWatched()
            dao.deleteSearches()
            contentDao.deleteOrphanPosts()
        }
    }
}
