package com.theoriacodex.app.media

import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.app.viewer.EpisodeClipTracks
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PostDownloadJobStoreGoldenTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a v3 queue the Gson store wrote restores the same jobs (ADR 0034)`() = runTest {
        val file = temporary.newFile("queue.json")
        file.writeText(checkNotNull(javaClass.getResource("/golden/download-queue/gson-written.json")).readText())

        val batches = FileBackedPostDownloadJobStore(file).load()

        assertEquals(listOf(1L to "Codex & <friends>", 4L to "Done"), batches.map { it.id to it.title })
        assertEquals(
            listOf(
                "2|PIXIV|100|Tom & Jerry's <cut>|m-1|Codex · A=B|false|QUEUED|77|true|2/5",
                "3|custom_safebooru_org|a'b|null|m-2|Downloads|true|FAILED|null|false|null",
                "5|GELBOORU|9|é☃|m-3|Downloads|false|SAVED|null|false|null",
            ),
            batches.flatMap { it.items }.map(::summary),
        )
        assertTrue(batches.flatMap { it.items }.all { it.request.restored })
    }

    @Test
    fun `the v3 download queue golden pins every stored key (ADR 0034)`() {
        assertStoredFormat(
            checkNotNull(javaClass.getResource("/golden/download-queue/gson-written.json")).readText() to DownloadQueueFile(
                3,
                listOf(
                    DownloadBatchRecord(
                        1, "Codex & <friends>",
                        listOf(
                            DownloadItemRecord(
                                2, "PIXIV", "100", "Tom & Jerry's <cut>", "m-1", "Codex · A=B", null, "DOWNLOADING", 77, true, 2, 5,
                            ),
                            DownloadItemRecord(
                                3, "custom_safebooru_org", "a'b", null, "m-2", "Downloads", true, "FAILED", null, false, null, null,
                            ),
                        ),
                    ),
                    DownloadBatchRecord(
                        4, "Done", listOf(DownloadItemRecord(5, "GELBOORU", "9", "é☃", "m-3", "Downloads", null, "SAVED", null, false, null, null)),
                    ),
                ),
            ),
            checkNotNull(javaClass.getResource("/golden/download-queue/clip-written.json")).readText() to DownloadQueueFile(
                3,
                listOf(
                    DownloadBatchRecord(
                        1, "Clip",
                        listOf(
                            DownloadItemRecord(
                                2, "HITOMI", "7", "Episode 7", "m-1", "Clip 1:01–1:31", null, "ENCODING", null, false, null, null,
                                clipStartMs = 61_000L, clipEndMs = 91_000L,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a v1 queue infers untracked preparation and a neutral origin`() = runTest {
        val file = temporary.newFile("queue.json")
        file.writeText(
            """{"version":1,"batches":[{"id":1,"title":"Old","items":[""" +
                """{"id":2,"source":"PIXIV","postId":"5","marker":"m","status":"PREPARING","enqueuePending":false},""" +
                """{"id":3,"source":"PIXIV","postId":"6","marker":"n","status":"SAVED","enqueuePending":false}]}]}""",
        )

        assertEquals(
            listOf("2|PIXIV|5|null|m|Downloads|true|QUEUED|null|false|null", "3|PIXIV|6|null|n|Downloads|false|SAVED|null|false|null"),
            FileBackedPostDownloadJobStore(file).load().single().items.map(::summary),
        )
    }

    @Test
    fun `an unreadable queue fails instead of dropping jobs`() = runTest {
        val file = temporary.newFile("queue.json")
        val store = FileBackedPostDownloadJobStore(file)
        for (text in listOf("""{"version":4,"batches":[]}""", """{"version":3,"batches":[{"id":0,"title":"x","items":[]}]}""",
                """{"version":3,"batches":[{"id":1,"title":"x","items":[{"id":2,"source":"PIXIV","postId":"1","marker":"m","status":"LOST"}]}]}""",
                "{")) {
            file.writeText(text)
            assertTrue(text, runCatching { store.load() }.isFailure)
        }
    }

    @Test
    fun `a clip keeps only its range across restarts, and a damaged clip row is dropped alone (ADR 0223)`() = runTest {
        val file = temporary.newFile("queue.json")
        val store = FileBackedPostDownloadJobStore(file)
        val media = ImageRef("https://example.test/poster.jpg", null, "image/jpeg")
        val post = Post(PostId(SourceKey.PIXIV, "7"), media, media, listOf(media), null, null, null,
            emptyList(), emptyList(), null, null, title = "Episode 7")
        val clip = EpisodeClipRequest(EpisodeClipRange(61_000L, 91_000L),
            MediaStream("https://example.test/e7.m3u8", "Main", headers = mapOf("Cookie" to "secret")), EpisodeClipTracks(subtitles = true, subtitleIndex = 0, audioIndex = 0))
        val item = PostDownloadItem(2, PostDownloadRequest(post, clip = clip), PostDownloadStatus.ENCODING)
        store.save(listOf(PostDownloadBatch(1, "Clip", listOf(item))))

        val restored = store.load().single().items.single()
        assertEquals(EpisodeClipRequest(clip.range, stream = null), restored.request.clip)
        assertEquals(PostDownloadStatus.QUEUED, restored.status)
        assertTrue("secret" !in file.readText() && "e7.m3u8" !in file.readText())

        file.writeText(Regex(""""clipEndMs"\s*:\s*91000""").replace(file.readText(), "\"clipEndMs\": 999999"))
        assertEquals(emptyList<PostDownloadBatch>(), store.load())
    }

    private fun summary(item: PostDownloadItem): String = with(item.request) {
        listOf(item.id, post.id.source.id, post.id.sourcePostId, post.title, marker, originLabel, untrackedPreparation,
            item.status, item.downloadId, item.enqueuePending, selectedMedia?.let { "${it.pageIndex}/${it.totalPages}" },
        ).joinToString("|")
    }
}
