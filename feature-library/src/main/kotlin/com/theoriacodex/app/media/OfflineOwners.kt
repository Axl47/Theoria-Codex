package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId

fun codexOfflineOwner(codexId: String): String = CODEX_OWNER_PREFIX + codexId

internal fun codexIdOfOfflineOwner(owner: String): String? =
    owner.takeIf { it.startsWith(CODEX_OWNER_PREFIX) }?.removePrefix(CODEX_OWNER_PREFIX)

internal fun codexOfflineStates(
    owners: List<OfflineStorageOwner>,
    members: Map<String, Set<PostId>>,
    available: Set<PostId>,
): Map<String, CodexOfflineState> =
    owners.mapNotNull { owner ->
        val codexId = codexIdOfOfflineOwner(owner.id) ?: return@mapNotNull null
        val posts = members[codexId] ?: return@mapNotNull null
        val job = owner.job
        if (job?.isCancelled == true && owner.availablePosts == 0 && !owner.preparing) return@mapNotNull null
        val failedPosts = job?.failedPosts.orEmpty()
        val settled = posts.count { it in available || it in failedPosts }
        codexId to CodexOfflineState(
            fraction = if (posts.isEmpty()) 1f else settled.toFloat() / posts.size,
            running = owner.preparing || job?.isRunning == true,
            failed = job?.let { !it.isRunning && (it.failureReason != null || failedPosts.any(posts::contains)) } == true,
        )
    }.toMap()

private const val CODEX_OWNER_PREFIX = "codex:"
