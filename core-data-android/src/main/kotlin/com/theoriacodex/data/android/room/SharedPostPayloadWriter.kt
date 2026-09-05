package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.Post
import com.theoriacodex.data.repository.mergeSparsePost

/**
 * Transaction-local owner for sparse writes into the shared posts table.
 *
 * Save, Like and Recents may receive a route snapshot before lazy media resolution finishes. Those absent fields
 * must not erase richer data already owned by Codex or Likes, while newly known values should still
 * refresh the shared payload.
 */
internal class SharedPostPayloadWriter(
    private val dao: CodexLikesDao,
    private val codec: LocalPostPayloadCodec,
) {
    fun upsert(post: Post) {
        val source = post.id.source.name
        val sourcePostId = post.id.sourcePostId
        val existingEntity = dao.post(source, sourcePostId)
        if (existingEntity == null) {
            val inserted = dao.insertPost(PostEntity(source, sourcePostId, codec.encode(post)))
            if (inserted != -1L) return
        }

        val current = requireNotNull(existingEntity ?: dao.post(source, sourcePostId)) {
            "Shared Post disappeared while merging $source:$sourcePostId"
        }
        val merged = mergeSparsePost(codec.decode(current), post)
        val payload = codec.encode(merged)
        if (payload != current.payloadJson) {
            check(dao.updatePost(source, sourcePostId, payload) == 1) {
                "Shared Post disappeared while updating $source:$sourcePostId"
            }
        }
    }
}
