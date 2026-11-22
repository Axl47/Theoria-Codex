package com.theoriacodex.app.post

import com.theoriacodex.domain.model.PictureHashIndex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which posts known this session are copies of one another on different sites: the same file ([Post.fileHash]) or the
 * same picture ([Post.pictureHash], see [com.theoriacodex.domain.model.SAME_PICTURE_MAX_DISTANCE]). Copies of copies
 * form one group. Feeds call [learn] with the posts they list, and
 * [com.theoriacodex.app.ui.routes.BrowsingMetadataViewModel] with the stored library and watch history and, through
 * [learnPictures], the library's stored picture hashes, so a copy of a liked, saved or watched post counts as liked,
 * saved or watched. Nothing is persisted.
 */
class FileMirrorIndex {
    private val lock = Any()

    /** Every learned post's group; members of one group share the same set. */
    private val groupOf = HashMap<PostId, MutableSet<PostId>>()
    private val idByFile = HashMap<String, PostId>()
    private val filed = HashSet<PostId>()
    private val pictured = HashSet<PostId>()
    private val pictures = PictureHashIndex<PostId>()
    private val mutableLinks = MutableStateFlow(0)

    /** Grows whenever two known posts are first found to be copies; readers of [withCopies] re-read then. */
    val links: StateFlow<Int> = mutableLinks.asStateFlow()

    fun learn(posts: Iterable<Post>) {
        // A stored post can lack the hashes a listing has, so it stays open to a later listing that has them.
        learnAll(posts.map { Triple(it.id, it.fileHash, it.pictureHash) })
    }

    /** Teaches picture hashes known without their posts, such as the Library's stored ones. */
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

    /** Joins the groups of [a] and [b]; returns whether they were apart. */
    private fun link(a: PostId, b: PostId): Boolean {
        val first = groupOf.getValue(a)
        val second = groupOf.getValue(b)
        if (first === second) return false
        val (into, from) = if (first.size >= second.size) first to second else second to first
        into += from
        from.forEach { groupOf[it] = into }
        return true
    }

    /** [ids] plus every known post that is a copy of one of them; [ids] itself when there are none. */
    fun withCopies(ids: Set<PostId>): Set<PostId> = synchronized(lock) {
        val copies = ids.flatMapTo(HashSet()) { id -> groupOf[id]?.takeIf { it.size > 1 }.orEmpty() }
        if (copies.isEmpty()) ids else ids + copies
    }

    /** Known posts that are copies of [post], [post] itself included, whether or not [post] was learned. */
    fun copiesOf(post: Post): Set<PostId> = synchronized(lock) {
        val found = hashSetOf(post.id)
        groupOf[post.id]?.let(found::addAll)
        post.fileHash?.let(idByFile::get)?.let(groupOf::get)?.let(found::addAll)
        post.pictureHash?.let { hash -> pictures.matches(hash).forEach { id -> groupOf[id]?.let(found::addAll) } }
        found
    }
}
