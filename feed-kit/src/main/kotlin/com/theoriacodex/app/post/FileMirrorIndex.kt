package com.theoriacodex.app.post

import com.theoriacodex.domain.model.PictureHashIndex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FileMirrorIndex {
    private val lock = Any()

    private val groupOf = HashMap<PostId, MutableSet<PostId>>()
    private val idByFile = HashMap<String, PostId>()
    private val filed = HashSet<PostId>()
    private val pictured = HashSet<PostId>()
    private val pictures = PictureHashIndex<PostId>()
    private val mutableLinks = MutableStateFlow(0)

    val links: StateFlow<Int> = mutableLinks.asStateFlow()

    fun learn(posts: Iterable<Post>) {
        learnAll(posts.map { Triple(it.id, it.fileHash, it.pictureHash) })
    }

    fun learnPictures(hashes: Map<PostId, Long>) {
        learnAll(hashes.map { (id, hash) -> Triple(id, null, hash) })
    }

    private fun learnAll(entries: List<Triple<PostId, String?, Long?>>) {
        var linked = false
        synchronized(lock) {
            for ((id, file, picture) in entries) {
                groupOf.getOrPut(id) { mutableSetOf(id) }
                if (file != null && filed.add(id)) linked = link(id, idByFile.getOrPut(file) { id }) or linked
                if (picture != null && pictured.add(id)) {
                    pictures.matches(picture).forEach { other -> linked = link(id, other) or linked }
                    pictures.add(id, picture)
                }
            }
        }
        if (linked) mutableLinks.value += 1
    }

    private fun link(a: PostId, b: PostId): Boolean {
        val first = groupOf.getValue(a)
        val second = groupOf.getValue(b)
        if (first === second) return false
        val (into, from) = if (first.size >= second.size) first to second else second to first
        into += from
        from.forEach { groupOf[it] = into }
        return true
    }

    fun withCopies(ids: Set<PostId>): Set<PostId> = synchronized(lock) {
        val copies = ids.flatMapTo(HashSet()) { id -> groupOf[id]?.takeIf { it.size > 1 }.orEmpty() }
        if (copies.isEmpty()) ids else ids + copies
    }

    fun copiesOf(post: Post): Set<PostId> = synchronized(lock) {
        val found = hashSetOf(post.id)
        groupOf[post.id]?.let(found::addAll)
        post.fileHash?.let(idByFile::get)?.let(groupOf::get)?.let(found::addAll)
        post.pictureHash?.let { hash -> pictures.matches(hash).forEach { id -> groupOf[id]?.let(found::addAll) } }
        found
    }
}
