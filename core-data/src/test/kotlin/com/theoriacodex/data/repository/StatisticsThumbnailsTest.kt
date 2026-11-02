package com.theoriacodex.data.repository

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsThumbnailsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `promotion copies once survives other list demotion and removes final demotion`() = runTest {
        val directory = temporary.newFolder()
        val store = StatisticsThumbnailStore(directory)
        val posts = (1..6).associate { "post:$it" to CodexPostStatistics(opens = it.toLong()) } +
            ("post:1" to CodexPostStatistics(opens = 1L, videoMs = 20L))
        val stats = LifetimeStatistics(codexStatistics = mapOf("a" to CodexStatistics(posts = posts)))
        val promoted = promotedStatisticsPostKeys(stats)
        assertTrue("post:1" in promoted)
        var fetches = 0
        val fetch: suspend (File) -> Boolean = { it.writeText("cached thumbnail"); fetches++; true }
        val files = store.reconcile(promoted, promoted.associateWith { fetch })
        assertEquals(6, files.size)
        store.reconcile(promoted, promoted.associateWith { fetch })
        assertEquals(6, fetches)
        val remaining = promotedStatisticsPostKeys(stats.copy(codexStatistics = mapOf("a" to
            CodexStatistics(posts = posts + ("post:1" to CodexPostStatistics(opens = 1L))))))
        store.reconcile(remaining)
        assertFalse(File(files.getValue("post:1")).exists())
        assertEquals(5, directory.listFiles()!!.size)
        store.reconcile(emptySet())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `failed fetch discards partial file and later open can retry`() = runTest {
        val directory = temporary.newFolder()
        val store = StatisticsThumbnailStore(directory)
        val failed = store.reconcile(setOf("post"), mapOf("post" to { file ->
            file.writeText("partial")
            throw java.io.IOException("offline")
        }))
        assertTrue(failed.isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        val restored = store.reconcile(setOf("post"), mapOf("post" to { file -> file.writeText("ok"); true }))
        assertEquals("ok", File(restored.getValue("post")).readText())
        assertEquals(restored, StatisticsThumbnailStore(directory).reconcile(setOf("post")))
    }
}
