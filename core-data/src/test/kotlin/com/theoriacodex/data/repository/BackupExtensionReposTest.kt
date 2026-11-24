package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupExtensionReposTest {
    @Test
    fun `repos are added side by side, not one after another`() = runTest {
        val repos = AllStartFirst(expected = 3)
        val listed = List(3) { NewExtensionRepo("https://r$it.example/repo", "k$it") }

        // Sequentially, the first add would wait forever for the others to start.
        val result = withTimeout(1_000) { repos.addFromBackup(listed) }

        assertEquals(BackupReposAdded(added = 3), result)
    }

    @Test
    fun `one repo's refusal does not stop the others or count as added`() = runTest {
        val repos = AllStartFirst(expected = 2, refuse = "https://bad.example/repo")

        val result = repos.addFromBackup(listOf(NewExtensionRepo("https://ok.example/repo", "k1"), NewExtensionRepo("https://bad.example/repo", "k2")))

        assertEquals(BackupReposAdded(1, mapOf("https://bad.example/repo" to "Now signed with another key")), result)
    }
}

/** Each add waits until [expected] adds have started, so only concurrent adds finish. */
private class AllStartFirst(private val expected: Int, private val refuse: String = "") : ExtensionRepos {
    private var started = 0
    private val allStarted = CompletableDeferred<Unit>()
    override suspend fun repos(): List<ExtensionRepo> = emptyList()
    override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> {
        if (++started == expected) allStarted.complete(Unit)
        allStarted.await()
        return repos.filter { it.url == refuse }.associate { it.url to "Now signed with another key" }
    }
    override suspend fun remove(url: String) = error("unused")
    override suspend fun available(refresh: Boolean): RepoListing = error("unused")
    override suspend fun install(repo: String, packageName: String): String? = error("unused")
    override suspend fun uninstall(id: String) = error("unused")
}
