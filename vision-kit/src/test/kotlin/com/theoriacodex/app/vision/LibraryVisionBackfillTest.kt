package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionFrames
import com.theoriacodex.data.repository.MAX_FRAME_ATTEMPTS
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryVisionBackfillTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    /** The Library newest first, as Room answers; [stored] is what indexing wrote, one row per post. */
    private class Library(val posts: List<Post>) : VisualSignatureRepository {
        val stored = mutableListOf<PictureVision>()
        private val attempts = HashMap<PostId, Int>()
        private val tags = MutableStateFlow<Map<PostId, List<PredictedTag>>>(emptyMap())
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = flowOf(emptyMap())
        override suspend fun put(postId: PostId, hash: Long?) = Unit
        override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = emptyList()
        override suspend fun putVision(entries: List<PictureVision>) {
            for (entry in entries) {
                val at = stored.indexOfFirst { it.postId == entry.postId }
                if (at >= 0) stored[at] = entry else stored += entry
                attempts.remove(entry.postId)
            }
            tags.value = stored.mapNotNull { vision -> vision.tags?.let { vision.postId to it } }.toMap()
        }
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post> =
            posts.filter { post -> stored.none { it.postId == post.id } }.take(limit)
        override suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int): List<Post> =
            posts.filter { it.id in signaturesAwaitingFrames() }.take(limit)
        override suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean) {
            attempts[postId] = (attempts[postId] ?: 0) + if (permanently) MAX_FRAME_ATTEMPTS else 1
        }
        override suspend fun unwantFrames(postIds: List<PostId>, modelId: String) {
            stored.replaceAll { if (it.postId in postIds) it.copy(frames = VisionFrames.NONE) else it }
        }
        override fun observePredictedTags(modelId: String): Flow<Map<PostId, List<PredictedTag>>> = tags
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = flowOf(VisionProgress(0, 0))
        override suspend fun clearVision() = stored.clear()

        fun vision(id: String) = stored.single { it.postId.sourcePostId == id }

        fun signaturesAwaitingFrames(): List<PostId> =
            stored.filter { it.frames == VisionFrames.WANTED && (attempts[it.postId] ?: 0) < MAX_FRAME_ATTEMPTS }.map { it.postId }
    }

    private val model = ByteArray(1_000) { it.toByte() }
    private val words = "tag_id,name,category,count\n1,solo,0,10\n".toByteArray()
    private val spec = VisualModelSpec(
        "test-model-1", "https://models.test/", ModelFile("model.tflite", model.size.toLong(), sha(model)),
        ModelFile("tags.csv", words.size.toLong(), sha(words)),
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readyStore(): VisualModelStore {
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
    fun `previews that fail to load are skipped, so the older Library is still indexed, and a later run tries them again`() = runBlocking {
        val dead = List(40) { post("dead-$it") }
        val older = List(3) { post("no-still-$it") }
        val library = Library(dead + older)
        val store = readyStore()
        assertEquals(ModelState.Ready, store.states.value)
        // Posts without a still are settled without the model; the dead previews never load.
        val tagger = PictureTagger(store, library, Pictures(hasStill = { it.id.sourcePostId.startsWith("dead") }), scope)

        assertTrue(backfill(tagger, library).drain())

        assertEquals(older.map(Post::id), library.stored.map(PictureVision::postId))
    }

    @Test
    fun `a model that will not load asks for another run while it is installed, and none once it is gone`() = runBlocking {
        val library = Library(listOf(post("a")))
        // The stored file is no model, so loading it fails as it might under memory pressure.
        val installed = readyStore()
        val picture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val tagger = PictureTagger(installed, library, Pictures(still = { picture }), scope)
        assertTrue(backfill(tagger, library).drain())
        assertEquals(emptyList<PictureVision>(), library.stored)

        installed.modelFile.delete()
        val gone = VisualModelStore(spec, folder.root, OkHttpClient(), scope) { }
        assertFalse(backfill(PictureTagger(gone, library, Pictures(still = { picture }), scope), library).drain())
    }

    @Test
    fun `a playing post is stored from its still first, then upgraded with its frames, each repeated scene once`() = runBlocking {
        val library = Library(listOf(post("video"), post("plain")))
        val analysed = mutableListOf<Pattern>()
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { it.id.sourcePostId == "video" },
            frames = { PostFrames.Read(listOf(Pattern.LEFT.bitmap(), Pattern.LEFT.bitmap(), Pattern.QUARTERS.bitmap())) },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(analysed) })
        val backfill = backfill(tagger, library)

        assertFalse(backfill.drain())
        assertEquals(listOf(VisionFrames.WANTED, VisionFrames.NONE), library.stored.map(PictureVision::frames))
        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), library.vision("video").tags)
        analysed.clear()

        assertFalse(backfill.drainFrames())

        // Four pictures, so each tag scores the mean of its top two: the still's 0.9 and the quarters' 0.6.
        val upgraded = library.vision("video")
        assertEquals(VisionFrames.READ, upgraded.frames)
        assertEquals(listOf(PredictedTag("solo", 0.75f, PredictedTag.Kind.GENERAL)), upgraded.tags)
        assertEquals(listOf(Pattern.TOP, Pattern.LEFT, Pattern.QUARTERS), analysed)
        assertTrue(library.signaturesAwaitingFrames().isEmpty())
    }

    @Test
    fun `frames that cannot be read leave the still analysis standing, tried once a run and a few times at most`() = runBlocking {
        val library = Library(listOf(post("offline"), post("broken"), post("picture")))
        var framesRead = 0
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { it.id.sourcePostId != "picture" },
            frames = {
                framesRead++
                if (it.id.sourcePostId == "offline") PostFrames.Unreachable else PostFrames.Unreadable
            },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val backfill = backfill(tagger, library)
        backfill.drain()
        val stills = library.stored.toList()

        // A failure asks for no early rerun: the next scheduled run tries again.
        assertFalse(backfill.drainFrames())
        assertEquals(2, framesRead)
        assertEquals(stills, library.stored)
        assertEquals(listOf(post("offline").id), library.signaturesAwaitingFrames())

        repeat(MAX_FRAME_ATTEMPTS) { backfill.drainFrames() }
        assertEquals(1 + MAX_FRAME_ATTEMPTS, framesRead)
        assertTrue(library.signaturesAwaitingFrames().isEmpty())
        assertEquals(stills, library.stored)
    }

    @Test
    fun `posts failing to be reached one after another stop the run and count once, and posts that stopped playing are set aside`() = runBlocking {
        val library = Library(listOf("a", "b", "c", "d", "gif").map(::post))
        var read = 0
        var gifPlays = true
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { it.id.sourcePostId != "gif" || gifPlays },
            frames = { read++; PostFrames.Unreachable },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val backfill = backfill(tagger, library)
        backfill.drain()
        gifPlays = false

        // The network is down rather than these posts: stop early and ask for another run.
        assertTrue(backfill.drainFrames())
        assertEquals(3, read)
        assertEquals(VisionFrames.NONE, library.vision("gif").frames)
        assertEquals(listOf("a", "b", "c", "d").map { post(it).id }, library.signaturesAwaitingFrames())

        // Each run charges only the streak's first post, so the others keep their tries.
        repeat(MAX_FRAME_ATTEMPTS - 1) { backfill.drainFrames() }
        assertEquals(listOf("b", "c", "d").map { post(it).id }, library.signaturesAwaitingFrames())
    }

    @Test
    fun `a device running warm stops the frame upgrade and asks for another run`() = runBlocking {
        val library = Library(listOf(post("video")))
        var warm = false
        val pictures = Pictures(still = { Pattern.TOP.bitmap() }, plays = { true }, frames = { error("a warm device reads no frames") })
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val backfill = LibraryVisionBackfill(tagger, library, hot = { warm })
        backfill.drain()
        warm = true

        assertTrue(backfill.drainFrames())
        assertEquals(VisionFrames.WANTED, library.vision("video").frames)
    }

    @Test
    fun `what the Library stored for a post wins over this session's look at its still`() = runBlocking {
        val library = Library(listOf(post("video")))
        val pictures = Pictures(
            still = { Pattern.LEFT.bitmap() },
            plays = { true },
            frames = { PostFrames.Read(listOf(Pattern.TOP.bitmap(), Pattern.TOP.bitmap())) },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val video = post("video")
        // For You looks at the still: the 0.1 score stores no tag.
        tagger.analyze(video)
        assertEquals(emptyList<PredictedTag>(), tagger.known(video.id))

        library.putVision(listOf(PictureVision(video.id, tagger.modelId, byteArrayOf(1), listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)))))
        withTimeout(10_000) { tagger.library.first { it[video.id] != null } }

        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), tagger.known(video.id))
    }

    private fun backfill(tagger: PictureTagger, library: Library) = LibraryVisionBackfill(tagger, library, hot = { false })

    /** Stills and frames as a test sets them; by default every post has a still that fails to load and none plays. */
    private class Pictures(
        private val hasStill: (Post) -> Boolean = { true },
        private val still: (Post) -> Bitmap? = { null },
        private val plays: (Post) -> Boolean = { false },
        private val frames: suspend (Post) -> PostFrames = { error("only playing posts read frames") },
    ) : PictureSource {
        override fun hasStill(post: Post): Boolean = hasStill.invoke(post)
        override suspend fun still(post: Post): Bitmap? = still.invoke(post)
        override fun plays(post: Post): Boolean = plays.invoke(post)
        override suspend fun frames(post: Post): PostFrames = frames.invoke(post)
    }

    /** Scores the one-tag vocabulary by [Pattern]: 0.9 for the top half dark, 0.1 for the left, 0.6 for quarters. */
    private class PatternModel(private val analysed: MutableList<Pattern>) : PictureModel {
        override fun analyze(picture: Bitmap): PictureAnalysis {
            val pattern = Pattern.of(picture).also(analysed::add)
            val score = when (pattern) {
                Pattern.TOP -> 0.9f
                Pattern.LEFT -> 0.1f
                Pattern.QUARTERS -> 0.6f
            }
            return PictureAnalysis(embedding = floatArrayOf(1f, 0f), scores = floatArrayOf(score))
        }

        override fun close() = Unit
    }
}
