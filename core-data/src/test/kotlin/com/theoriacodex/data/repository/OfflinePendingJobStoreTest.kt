package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePendingJobStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a queue the Gson store wrote loads the same requests`() = runTest {
        val file = temporary.newFile("offline_jobs.json")
        file.writeText(checkNotNull(javaClass.getResource("/golden/offline-queue/gson-written.json")).readText())

        assertEquals(
            mapOf(
                "codex:a&b" to listOf(
                    PostId(SourceKey.PIXIV, "1"),
                    PostId(SourceKey.parseOrThrow("custom_safebooru_org"), "x<y>'z'="),
                ),
                "automatic" to emptyList(),
            ),
            OfflinePendingJobStore(file).load(),
        )
    }

    @Test
    fun `saved requests reload and an unreadable queue fails instead of dropping work`() = runTest {
        val file = temporary.root.resolve("queue.json")
        val requests = mapOf("codex:x" to listOf(PostId(SourceKey.PIXIV, "7"), PostId(SourceKey.PIXIV, "7")))
        val store = OfflinePendingJobStore(file)
        store.save(requests)
        assertEquals(mapOf("codex:x" to listOf(PostId(SourceKey.PIXIV, "7"))), store.load())

        for (text in listOf("""{"version":2,"owners":[]}""", """{"version":1,"owners":[{"owner":" ","posts":[]}]}""", "{")) {
            file.writeText(text)
            assertTrue(text, runCatching { store.load() }.isFailure)
        }
    }
}
