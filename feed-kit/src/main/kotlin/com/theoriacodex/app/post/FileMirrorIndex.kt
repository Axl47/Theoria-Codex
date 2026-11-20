package com.theoriacodex.app.post

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which posts known this session are copies of one file on different sites, by [Post.fileHash]. Feeds call [learn]
 * with the posts they list, and [com.theoriacodex.app.ui.routes.BrowsingMetadataViewModel] with the stored library and
 * watch history, so a copy of a liked, saved or watched file counts as liked, saved or watched. Nothing is persisted.
 */
class FileMirrorIndex {
    private val lock = Any()
    private val hashById = HashMap<PostId, String>()
    private val idsByHash = HashMap<String, MutableSet<PostId>>()
    private val mutableLinks = MutableStateFlow(0)

    /** Grows whenever two known posts are first found to share a file; readers of [withCopies] re-read then. */
    val links: StateFlow<Int> = mutableLinks.asStateFlow()

    fun learn(posts: Iterable<Post>) {
        var linked = false
        synchronized(lock) {
            for (post in posts) {
                if (post.id in hashById) continue
                // A stored post can lack the hash its site reports, so it stays open to a later listing that has it.
                val hash = post.fileHash ?: continue
                hashById[post.id] = hash
                val ids = idsByHash.getOrPut(hash) { linkedSetOf() }
                ids += post.id
                if (ids.size > 1) linked = true
            }
        }
        if (linked) mutableLinks.value += 1
    }

    /** [ids] plus every known post that shares a file with one of them; [ids] itself when there are none. */
    fun withCopies(ids: Set<PostId>): Set<PostId> = synchronized(lock) {
        val copies = ids.flatMapTo(HashSet()) { id -> hashById[id]?.let(idsByHash::get)?.takeIf { it.size > 1 }.orEmpty() }
        if (copies.isEmpty()) ids else ids + copies
    }

    /** Known posts holding the same file as [post], [post] itself included. */
    fun copiesOf(post: Post): Set<PostId> {
        val hash = post.fileHash ?: return setOf(post.id)
        return synchronized(lock) { idsByHash[hash].orEmpty() + post.id }
    }
}
