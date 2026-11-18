package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.mergeSparsePost
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.domain.model.Post

/**
 * Transaction-local owner for sparse writes into the shared posts table.
 *
 * Save, Like and Recents may receive a route snapshot before lazy media resolution finishes. Those absent fields
 * must not erase richer data already owned by Codex or Likes, while newly known values should still
 * refresh the shared payload. A row is rewritten only when a stored value changes, so rows older builds wrote
 * keep their bytes until the post itself changes.
 */
internal class SharedPostPayloadWriter(
    private val dao: CodexLikesDao,
) {
    fun upsert(post: Post) {
        val source = post.id.source.id
        val sourcePostId = post.id.sourcePostId
        val existingEntity = dao.post(source, sourcePostId)
        if (existingEntity == null) {
            val inserted = dao.insertPost(PostEntity(source, sourcePostId, LocalPostPayloadCodec.encode(post)))
            if (inserted != -1L) return
        }

        val current = requireNotNull(existingEntity ?: dao.post(source, sourcePostId)) {
            "Shared Post disappeared while merging $source:$sourcePostId"
        }
        val existing = LocalPostPayloadCodec.decode(current)
        val merged = mergeSparsePost(existing, post)
        // Compare stored records, not bytes: the encoder no longer writes the escapes Gson did, and live-only Post
        // fields such as the comment hint are never stored.
        if (PostStorageCodec.encode(merged) != PostStorageCodec.encode(existing)) {
            check(dao.updatePost(source, sourcePostId, LocalPostPayloadCodec.encode(merged)) == 1) {
                "Shared Post disappeared while updating $source:$sourcePostId"
            }
        }
    }
}
