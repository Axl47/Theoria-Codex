package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.storedRepoAddress

internal data class BackupReposAdded(val added: Int = 0, val problems: Map<String, String> = emptyMap())

internal fun List<ExtensionRepo>.notIn(stored: List<ExtensionRepo>): List<ExtensionRepo> {
    val addresses = stored.mapTo(HashSet()) { it.url }
    return distinctBy { storedRepoAddress(it.url) }.filter { storedRepoAddress(it.url) !in addresses }
}

internal suspend fun ExtensionRepos.restoreFromBackup(listed: List<ExtensionRepo>): BackupReposAdded {
    val missing = listed.notIn(repos())
    val problems = restore(listed)
    return BackupReposAdded(missing.count { it.url !in problems }, problems)
}
