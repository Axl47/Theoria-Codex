package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryVisionBackfillTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    /** The Library newest first, as Room answers; [stored] is what indexing wrote. */
    private class Library(val posts: List<Post>) : VisualSignatureRepository {
        val stored = mutableListOf<PictureVision>()
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = flowOf(emptyMap())
        override suspend fun put(postId: PostId, hash: Long?) = Unit
        override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = emptyList()
        override suspend fun putVision(entries: List<PictureVision>) {
            stored += entries
        }
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post> =
            posts.filter { post -> stored.none { it.postId == post.id } }.take(limit)
        override fun observePredictedTags(modelId: String): Flow<Map<PostId, List<PredictedTag>>> = flowOf(emptyMap())
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = flowOf(VisionProgress(0, 0))
        override suspend fun clearVision() = stored.clear()
    }

    private fun readyStore(): VisualModelStore {
        val model = ByteArray(1_000) { it.toByte() }
        val words = "tag_id,name,category,count\n1,solo,0,10\n".toByteArray()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val spec = VisualModelSpec(
            "test-model-1", "https://models.test/", ModelFile("model.tflite", model.size.toLong(), sha(model)),
            ModelFile("tags.csv", words.size.toLong(), sha(words)),
        )
        val folders = VisualModelStore(spec, folder.root, OkHttpClient(), scope) { }
        folders.modelFile.parentFile!!.mkdirs()
        folders.modelFile.writeBytes(model)
        folders.vocabularyFile.writeBytes(words)
        return VisualModelStore(spec, folder.root, OkHttpClient(), scope) { }
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.DANBOORU, id), preview = ImageRef("https://cdn.test/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )

    @Test
    fun `previews that fail to load are skipped, so the older Library is still indexed`() = runBlocking {
        val dead = List(40) { post("dead-$it") }
        val older = List(3) { post("no-still-$it") }
        val library = Library(dead + older)
        val store = readyStore()
        assertEquals(ModelState.Ready, store.states.value)
        // Posts without a still are settled without the model; the dead previews never load.
        val tagger = PictureTagger(store, library, hasStill = { it.id.sourcePostId.startsWith("dead") }, loadStill = { null }, scope = scope)

        assertFalse(LibraryVisionBackfill(tagger, library, hot = { false }).drain())

        assertEquals(older.map(Post::id), library.stored.map(PictureVision::postId))
    }
}
