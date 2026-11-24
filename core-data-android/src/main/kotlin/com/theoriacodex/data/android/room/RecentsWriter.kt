package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.PostId

internal class RecentsWriter(private val dao: RecentsDao, private val posts: SharedPosts) {
    fun upsertWatched(
        postId: PostId,
        section: String,
        viewedAtEpochMs: Long,
        origin: String,
        originQueryHash: String?,
        maxViewedMediaNumber: Int,
    ) {
        dao.upsertWatched(
            RecentWatchedEntity(
                postId.source.id,
                postId.sourcePostId,
                section,
                viewedAtEpochMs,
                dao.nextWatchedSequence(),
                origin,
                originQueryHash,
                maxViewedMediaNumber,
            ),
        )
    }

    fun upsertSearch(queryHash: String, payloadJson: String, searchedAtEpochMs: Long) {
        dao.upsertSearch(RecentSearchEntity(queryHash, payloadJson, searchedAtEpochMs, dao.nextSearchSequence()))
    }

    fun trimWatched(limit: Int) {
        val removed = dao.watchedBeyondLimit(limit)
        if (removed.isEmpty()) return
        dao.trimWatched(limit)
        posts.releaseIfOrphaned(removed.map { it.source to it.sourcePostId })
    }

    fun trimSearches(limit: Int) {
        dao.trimSearches(limit)
    }
}
