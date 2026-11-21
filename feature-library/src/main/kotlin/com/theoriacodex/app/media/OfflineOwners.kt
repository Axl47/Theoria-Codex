package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId

/** The offline owner key under which a Codex kept offline retains its posts. */
fun codexOfflineOwner(codexId: String): String = CODEX_OWNER_PREFIX + codexId

/** The Codex an offline owner key belongs to, or null for other owners (such as saved posts). */
internal fun codexIdOfOfflineOwner(owner: String): String? =
    owner.takeIf { it.startsWith(CODEX_OWNER_PREFIX) }?.removePrefix(CODEX_OWNER_PREFIX)

/**
 * The ring state of each Codex kept offline, by Codex id: the share of its current posts ([members]) that are
 * settled, meaning available offline ([available]) or failed in its last run, and whether its job runs or
 * failed (as a whole, or for a current post). Posts no longer in the Codex do not count. Codices whose members are not known yet get no ring.
 */
internal fun codexOfflineStates(
    owners: List<OfflineStorageOwner>,
    members: Map<String, Set<PostId>>,
    available: Set<PostId>,
): Map<String, CodexOfflineState> =
    owners.mapNotNull { owner ->
        val codexId = codexIdOfOfflineOwner(owner.id) ?: return@mapNotNull null
        val posts = members[codexId] ?: return@mapNotNull null
        val job = owner.job
        val failedPosts = job?.failedPosts.orEmpty()
        val settled = posts.count { it in available || it in failedPosts }
        codexId to CodexOfflineState(
            fraction = if (posts.isEmpty()) 1f else settled.toFloat() / posts.size,
            running = owner.preparing || job?.isRunning == true,
            // A failed post that has left the Codex no longer matters; a failure of the whole run always does.
            failed = job?.let { !it.isRunning && (it.failureReason != null || failedPosts.any(posts::contains)) } == true,
        )
    }.toMap()

private const val CODEX_OWNER_PREFIX = "codex:"
