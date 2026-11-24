package com.theoriacodex.data.repository

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.storedRepoAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What adding a backup's repos did: how many were new here and went in, and why each refused one was, by address given. */
internal data class BackupReposAdded(val added: Int = 0, val problems: Map<String, String> = emptyMap())

/** The repos [this] lists that are not among [stored], compared by their stored address. */
internal fun List<NewExtensionRepo>.notIn(stored: List<ExtensionRepo>): List<NewExtensionRepo> {
    val addresses = stored.mapTo(HashSet()) { it.url }
    return filter { storedRepoAddress(it.url) !in addresses }
}

/**
 * Adds the repos a backup names, for a Mihon-family import and a Theoria restore alike: [ExtensionRepos.add] skips
 * those already added and refuses one now signed with another key. Each repo is read over the network, so the missing
 * ones are added side by side rather than one after another; the backup screen waits for the slowest, not the sum.
 * A failure of the repos themselves (not of one repo) reports every repo as not added.
 */
internal suspend fun ExtensionRepos.addFromBackup(listed: List<NewExtensionRepo>): BackupReposAdded =
    runCatchingPreservingCancellation {
        val missing = listed.notIn(repos())
        val gate = Semaphore(MAX_PARALLEL_REPO_ADDS)
        val problems = coroutineScope {
            missing.map { repo -> async { gate.withPermit { add(listOf(repo)) } } }.awaitAll()
        }.fold(emptyMap<String, String>()) { all, one -> all + one }
        BackupReposAdded(missing.count { it.url !in problems }, problems)
    }.getOrElse { BackupReposAdded(problems = listed.associate { it.url to "Import the backup again to retry" }) }

private const val MAX_PARALLEL_REPO_ADDS = 8
