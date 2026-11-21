package com.theoriacodex.app.media

/** The offline owner key under which a Codex kept offline retains its posts. */
fun codexOfflineOwner(codexId: String): String = CODEX_OWNER_PREFIX + codexId

/** The Codex an offline owner key belongs to, or null for other owners (such as saved posts). */
internal fun codexIdOfOfflineOwner(owner: String): String? =
    owner.takeIf { it.startsWith(CODEX_OWNER_PREFIX) }?.removePrefix(CODEX_OWNER_PREFIX)

/**
 * The ring state of each Codex kept offline, by Codex id: retained posts over the Codex's post count, and
 * whether its job runs or failed. Codices whose post count is not known yet get no ring.
 */
internal fun codexOfflineStates(owners: List<OfflineStorageOwner>, postCounts: Map<String, Int>): Map<String, CodexOfflineState> =
    owners.mapNotNull { owner ->
        val codexId = codexIdOfOfflineOwner(owner.id) ?: return@mapNotNull null
        val total = postCounts[codexId] ?: return@mapNotNull null
        val job = owner.job
        codexId to CodexOfflineState(
            fraction = if (total == 0) 1f else (owner.availablePosts.toFloat() / total).coerceAtMost(1f),
            running = owner.preparing || job?.isRunning == true,
            failed = owner.message != null || job?.let { !it.isRunning && it.failureMessage != null } == true,
        )
    }.toMap()

private const val CODEX_OWNER_PREFIX = "codex:"
