package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

internal data class MediaDurationPostSnapshot(
    val postsById: Map<PostId, Post>,
    val keysByPostId: Map<PostId, MediaDurationKey>,
    val candidatesByKey: Map<MediaDurationKey, Post>,
    val knownDurationsByKey: Map<MediaDurationKey, Long>,
) {
    val observedKeys: Set<MediaDurationKey> = keysByPostId.values.toSet()

    companion object {
        val EMPTY = MediaDurationPostSnapshot(
            postsById = emptyMap(),
            keysByPostId = emptyMap(),
            candidatesByKey = emptyMap(),
            knownDurationsByKey = emptyMap(),
        )
    }
}

internal fun mediaDurationPostSnapshot(
    posts: List<Post>,
    keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey,
): MediaDurationPostSnapshot {
    val postsById = linkedMapOf<PostId, Post>()
    val keysByPostId = linkedMapOf<PostId, MediaDurationKey>()
    val candidatesByKey = linkedMapOf<MediaDurationKey, Post>()
    val knownDurationsByKey = linkedMapOf<MediaDurationKey, Long>()
    posts.forEach { post ->
        if (postsById.putIfAbsent(post.id, post) != null || !isAnimatedPost(post)) {
            return@forEach
        }
        val key = keyFactory(post)
        keysByPostId[post.id] = key
        val durationMs = animatedDurationMs(post)
        if (durationMs != null) {
            knownDurationsByKey[key] = durationMs
        } else {
            candidatesByKey[key] = post
        }
    }
    return MediaDurationPostSnapshot(
        postsById = postsById,
        keysByPostId = keysByPostId,
        candidatesByKey = candidatesByKey,
        knownDurationsByKey = knownDurationsByKey,
    )
}

internal fun mediaDurationKey(post: Post): MediaDurationKey {
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

internal fun knownMediaDurations(
    posts: List<Post>,
    states: Map<MediaDurationKey, MediaDurationState>,
    keysByPostId: Map<PostId, MediaDurationKey> = mediaDurationKeysByPostId(posts),
): Map<PostId, Long> = buildMap {
    posts.forEach { post ->
        val duration = animatedDurationMs(post)
            ?: keysByPostId[post.id]
                ?.let(states::get)
                ?.let { state -> (state as? MediaDurationState.Known)?.durationMs }
        if (duration != null) put(post.id, duration)
    }
}

internal fun durationStatesByPostId(
    posts: List<Post>,
    states: Map<MediaDurationKey, MediaDurationState>,
    keysByPostId: Map<PostId, MediaDurationKey> = mediaDurationKeysByPostId(posts),
): Map<PostId, MediaDurationState> = buildMap {
    posts.forEach { post ->
        keysByPostId[post.id]?.let(states::get)?.let { state -> put(post.id, state) }
    }
}

internal fun mediaDurationKeysByPostId(posts: List<Post>): Map<PostId, MediaDurationKey> {
    return posts.asSequence()
        .filter(::isAnimatedPost)
        .associate { post -> post.id to mediaDurationKey(post) }
}

internal fun isAuthoritativeDurationMedia(post: Post, ref: ImageRef): Boolean {
    val authoritative = authoritativeDurationRef(post) ?: return false
    return authoritative.durationIdentity() == ref.durationIdentity()
}

private fun authoritativeDurationIdentity(post: Post): String {
    val ref = authoritativeDurationRef(post)
    val location = ref?.localPath ?: ref?.url
    return location?.normalizeDurationIdentity()
        ?: "provider:${post.id.source.name}:${post.id.sourcePostId}"
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
