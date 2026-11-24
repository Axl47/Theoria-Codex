package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.ExtensionRepo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupExtensionReposTest {
    @Test
    fun `only repos not added here count, once per address, and a refused one is reported instead`() = runTest {
        val repos = RecordingRepos(refuse = "https://bad.example/repo", added = "https://present.example/repo")
        val listed = listOf(
            ExtensionRepo("https://present.example/repo", "Present", "aa11"),
            ExtensionRepo("https://new.example/repo/index.min.json", "New", "bb22"),
            ExtensionRepo("https://new.example/repo", "New again", "bb22"),
            ExtensionRepo("https://bad.example/repo", "Bad", "cc33"),
        )

        val result = repos.restoreFromBackup(listed)

        assertEquals(BackupReposAdded(1, mapOf("https://bad.example/repo" to "Names no signing key")), result)
    }
}
