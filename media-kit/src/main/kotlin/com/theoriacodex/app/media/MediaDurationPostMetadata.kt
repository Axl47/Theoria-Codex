package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

internal data class MediaDurationPostSnapshot(
    val postsById: Map<PostId, Post>,
    val keysByPostId: Map<PostId, MediaDurationKey>,
    /** Animated posts whose feed carries no duration, so a lookup must decide one. */
    val candidatesByKey: Map<MediaDurationKey, Post>,
) {
    val observedKeys: Set<MediaDurationKey> = keysByPostId.values.toSet()

    companion object {
        val EMPTY = MediaDurationPostSnapshot(
            postsById = emptyMap(),
            keysByPostId = emptyMap(),
            candidatesByKey = emptyMap(),
        )
    }
}

internal fun mediaDurationPostSnapshot(
    posts: List<Post>,
    keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey,
    previousPosts: List<Post>? = null,
    previousSnapshot: MediaDurationPostSnapshot? = null,
): MediaDurationPostSnapshot {
    val appendSeed = appendSeed(posts, previousPosts, previousSnapshot)
    val postsById = LinkedHashMap(appendSeed?.snapshot?.postsById.orEmpty())
    val keysByPostId = LinkedHashMap(appendSeed?.snapshot?.keysByPostId.orEmpty())
    val candidatesByKey = LinkedHashMap(appendSeed?.snapshot?.candidatesByKey.orEmpty())
    val newPosts = posts.subList(appendSeed?.newPostsStart ?: 0, posts.size)
    newPosts.forEach { post ->
        if (postsById.putIfAbsent(post.id, post) != null || !isAnimatedPost(post)) {
            return@forEach
        }
        val key = keyFactory(post)
        keysByPostId[post.id] = key
        if (animatedDurationMs(post) == null) candidatesByKey[key] = post
    }
    return MediaDurationPostSnapshot(
        postsById = postsById,
        keysByPostId = keysByPostId,
        candidatesByKey = candidatesByKey,
    )
}

data class DurationFilterMetadata(
    val knownDurationMsByPostId: Map<PostId, Long>,
    val stateByPostId: Map<PostId, MediaDurationState>,
    val readiness: DurationFilterReadiness = DurationFilterReadiness(0, 0),
    val isPrepared: Boolean = true,
    /** Revealed posts in stable order; null means a feed has nothing to hold back. */
    val presentedPosts: List<Post>? = null,
    val revealFrontier: Int = Int.MAX_VALUE,
    /**
     * Stands for [knownDurationMsByPostId] in a filter over exactly [presentedPosts]. Frames of one projection share it
     * while every presented post that stays in place keeps its length, so such a filter skips a length that changed
     * nothing shown and may filter just the posts a length newly revealed.
     */
    val presentedDurationsKey: Any = UNSCOPED_DURATIONS,
) {
    val blocksPaging: Boolean
        get() = !isPrepared || readiness.blocksPaging

    /** A hidden anchor is final once it is decided and revealed, or probing is disabled. */
    fun isVisibilityResolved(post: Post?, resolveInBackground: Boolean, canonicalIndex: Int? = null): Boolean {
        if (!isPrepared) return false
        if (post == null || !resolveInBackground || !isAnimatedPost(post) || animatedDurationMs(post) != null) return true
        return !stateByPostId[post.id].isUndecided && (canonicalIndex == null || canonicalIndex < revealFrontier)
    }

    /** New posts appended to this frame are being prepared, while its shown posts stay put. */
    fun stillPreparing(): DurationFilterMetadata = copy(
        isPrepared = false,
        readiness = readiness.copy(pendingCount = readiness.pendingCount.coerceAtLeast(1)),
    )

    companion object {
        private val UNSCOPED_DURATIONS = Any()
        val EMPTY = DurationFilterMetadata(emptyMap(), emptyMap())
        val RESOLVING = DurationFilterMetadata(
            emptyMap(), emptyMap(), DurationFilterReadiness(1, 1), isPrepared = false,
            presentedPosts = emptyList(), revealFrontier = 0,
        )
    }
}

/**
 * A route's duration decisions by post. Each decision belongs to the post version the route synchronized, so a post
 * with the same id but other media (a related shelf's copy, say) gets none: its own media was never looked up.
 */
class MediaDurationStates internal constructor(
    private val byPostId: Map<PostId, MediaDurationState>,
    private val decidedPosts: Map<PostId, Post>,
    private val decidedKeys: Map<PostId, MediaDurationKey>,
) {
    /** The decision for [post]'s media, or null while none was made for it. Only another post version is hashed. */
    operator fun get(post: Post): MediaDurationState? {
        val state = byPostId[post.id] ?: return null
        return state.takeIf { decidedPosts[post.id] === post || decidedKeys[post.id] == mediaDurationKey(post) }
    }

    companion object {
        val EMPTY = MediaDurationStates(emptyMap(), emptyMap(), emptyMap())
    }
}

/** [decisions] by post for a route that synchronized [postsById] and keyed its animated posts as [keysByPostId]. */
internal fun routeDurationStates(
    postsById: Map<PostId, Post>,
    keysByPostId: Map<PostId, MediaDurationKey>,
    decisions: Map<MediaDurationKey, MediaDurationState>,
): MediaDurationStates {
    val byPostId = buildMap { keysByPostId.forEach { (id, key) -> decisions[key]?.let { state -> put(id, state) } } }
    return MediaDurationStates(byPostId, postsById, keysByPostId)
}

/**
 * [states] for [posts], hashing each animated post's media once. Routes publish their own; this serves screens that read
 * the coordinator directly, such as the benchmark fixture.
 */
fun mediaDurationStates(posts: List<Post>, states: Map<MediaDurationKey, MediaDurationState>): MediaDurationStates {
    val snapshot = mediaDurationPostSnapshot(posts)
    return routeDurationStates(snapshot.postsById, snapshot.keysByPostId, states)
}

internal fun durationFilterMetadata(
    posts: List<Post>,
    states: MediaDurationStates,
    active: Boolean,
): DurationFilterMetadata {
    if (!active) return DurationFilterMetadata.EMPTY
    val byId = buildMap { posts.forEach { post -> states[post]?.let { state -> put(post.id, state) } } }
    return DurationFilterMetadata(
        knownDurationMsByPostId = knownMediaDurations(posts, states),
        stateByPostId = byId,
        readiness = durationFilterReadiness(posts, active, byId),
    )
}

private data class MediaDurationAppendSeed(
    val newPostsStart: Int,
    val snapshot: MediaDurationPostSnapshot,
)

private fun appendSeed(
    posts: List<Post>,
    previousPosts: List<Post>?,
    previousSnapshot: MediaDurationPostSnapshot?,
): MediaDurationAppendSeed? {
    if (previousPosts == null || previousSnapshot == null) return null
    if (posts.size < previousPosts.size) return null
    if (posts.subList(0, previousPosts.size) != previousPosts) return null
    return MediaDurationAppendSeed(previousPosts.size, previousSnapshot)
}

fun mediaDurationKey(post: Post): MediaDurationKey {
    val authoritative = authoritativeDurationIdentity(post)
    return MediaDurationKey(
        postId = post.id,
        mediaFingerprint = mediaDurationFingerprint(
            MediaDurationFingerprintInput(
                postId = post.id,
                normalizedAuthoritativeMediaIdentity = authoritative,
                mime = authoritativeDurationRef(post)?.mime,
                mediaCount = post.mediaCount,
            ),
        ),
    )
}

/** Each post's length: the feed's own, else a decided lookup's. */
fun knownMediaDurations(
    posts: List<Post>,
    states: MediaDurationStates,
): Map<PostId, Long> = buildMap {
    posts.forEach { post ->
        val duration = animatedDurationMs(post) ?: (states[post] as? MediaDurationState.Known)?.durationMs
        if (duration != null) put(post.id, duration)
    }
}

fun isAuthoritativeDurationMedia(post: Post, ref: ImageRef): Boolean {
    val authoritative = authoritativeDurationRef(post) ?: return false
    return authoritative.durationIdentity() == ref.durationIdentity()
}

private fun authoritativeDurationIdentity(post: Post): String {
    val ref = authoritativeDurationRef(post)
    val location = ref?.localPath ?: ref?.url
    return location?.normalizeDurationIdentity()
        ?: "provider:${post.id.source.id}:${post.id.sourcePostId}"
}

private fun authoritativeDurationRef(post: Post): ImageRef? {
    return authoritativeDurationProbeRef(post)
        ?: post.full?.takeIf { ref ->
            mediaKind(ref) == PostMediaKind.UGOIRA || isAnimatedImageMediaRef(ref)
        }
        ?: post.media.firstOrNull { ref ->
            mediaKind(ref) == PostMediaKind.UGOIRA || isAnimatedImageMediaRef(ref)
        }
}

private fun String.normalizeDurationIdentity(): String {
    return trim().substringBefore('#').substringBefore('?')
}

private fun ImageRef.durationIdentity(): String? {
    return (localPath ?: url)?.normalizeDurationIdentity()
}
