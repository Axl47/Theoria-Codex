package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.LikeTagRewriter
import com.theoriacodex.domain.model.Post

class RoomLikeTagRewriter(
    private val database: TheoriaRoomDatabase,
    private val pageSize: Int = PAGE_SIZE,
) : LikeTagRewriter {
    private val dao = database.codexLikesDao()

    override suspend fun rewriteLikeTags(merge: (stored: Post, tags: List<String>) -> List<String>): Int {
        var changed = 0
        var after = LikedPostEntity("", "", "", 0, "")
        while (true) {
            val page = database.withTransaction {
                dao.likesAfter(after.profileId, after.source, after.sourcePostId, pageSize).also { likes ->
                    changed += likes.count { like -> rewrite(like, merge) }
                }
            }
            if (page.size < pageSize) return changed
            after = page.last()
        }
    }

    private fun rewrite(like: LikedPostEntity, merge: (stored: Post, tags: List<String>) -> List<String>): Boolean {
        val tags = runCatching { LikedTagsCodec.decode(like.tagsJson) }.getOrNull() ?: return false
        val entity = dao.post(like.source, like.sourcePostId) ?: return false
        val stored = runCatching { LocalPostPayloadCodec.decode(entity) }.getOrNull() ?: return false
        val merged = merge(stored, tags)
        if (merged == tags) return false
        dao.updateLikeTags(like.profileId, like.source, like.sourcePostId, LikedTagsCodec.encode(merged))
        return true
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
