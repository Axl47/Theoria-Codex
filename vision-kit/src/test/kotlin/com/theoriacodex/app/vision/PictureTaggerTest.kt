package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PictureLook
import com.theoriacodex.domain.vision.PredictedTag
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PictureTaggerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private object EmptySignatures : VisualSignatureRepository {
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = flowOf(emptyMap())
        override suspend fun put(postId: PostId, hash: Long?) = Unit
        override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = emptyList()
        override suspend fun putVision(entries: List<PictureVision>) = Unit
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post> = emptyList()
        override suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int): List<Post> = emptyList()
        override suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean) = Unit
        override suspend fun unwantFrames(postIds: List<PostId>, modelId: String) = Unit
        override fun observePredictedTags(modelId: String): Flow<Map<PostId, List<PredictedTag>>> =
            flowOf(mapOf(PostId(SourceKey.DANBOORU, "stored") to listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL))))
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = flowOf(VisionProgress(0, 0))
        override suspend fun clearVision() = Unit
    }

    @Test
    fun `while the model is absent the tagger answers Unavailable and knows nothing`() = runBlocking {
        val spec = VisualModelSpec.wdVitTaggerV3("https://models.test/")
        val store = VisualModelStore(spec, folder.root, OkHttpClient(), scope) { }
        val tagger = PictureTagger(
            store = store,
            signatures = EmptySignatures,
            pictures = object : PictureSource {
                override fun hasStill(post: Post): Boolean = error("the model is not ready, so no picture is inspected")
                override suspend fun still(post: Post) = error("the model is not ready, so no picture is loaded")
                override fun plays(post: Post): Boolean = false
                override suspend fun frames(post: Post): PostFrames = error("no frames are read for a still post")
            },
            scope = scope,
        )
        val post = Post(
            id = PostId(SourceKey.DANBOORU, "1"), preview = ImageRef("https://cdn.example/1.jpg", null, "image/jpeg"), full = null,
            pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null,
            createdAtEpochMs = null,
        )

        assertFalse(tagger.ready.value)
        assertEquals(PictureResult.Unavailable, tagger.analyze(post))
        assertNull(tagger.remembered(post.id))
        assertEquals(PictureLook.Blind, tagger.look(post))
        assertNull(tagger.known(post.id))
    }
}
