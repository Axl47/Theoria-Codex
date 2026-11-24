package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.storedRepoAddress

/** What restoring a backup's repos did: how many were new here and went in, and why each refused one was, by address given. */
internal data class BackupReposAdded(val added: Int = 0, val problems: Map<String, String> = emptyMap())

/** The repos [this] lists that are not among [stored], one per stored address. */
internal fun List<ExtensionRepo>.notIn(stored: List<ExtensionRepo>): List<ExtensionRepo> {
    val addresses = stored.mapTo(HashSet()) { it.url }
    return distinctBy { storedRepoAddress(it.url) }.filter { storedRepoAddress(it.url) !in addresses }
}

/**
 * Restores the repos a backup lists, for a Mihon-family import and a Theoria restore alike, through
 * [ExtensionRepos.restore]: no network, and one change to the stored repos.
 */
internal suspend fun ExtensionRepos.restoreFromBackup(listed: List<ExtensionRepo>): BackupReposAdded {
    val missing = listed.notIn(repos())
    val problems = restore(listed)
    return BackupReposAdded(missing.count { it.url !in problems }, problems)
}
