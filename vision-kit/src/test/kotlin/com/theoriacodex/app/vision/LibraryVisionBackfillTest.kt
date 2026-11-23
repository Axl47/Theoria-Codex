package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** The Library newest first, as Room answers; [stored] is what indexing wrote. */
    private class Library(val posts: List<Post>) : VisualSignatureRepository {
        val stored = mutableListOf<PictureVision>()
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = flowOf(emptyMap())
        override suspend fun put(postId: PostId, hash: Long?) = Unit
        override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = emptyList()
        override suspend fun putVision(entries: List<PictureVision>) {
            stored += entries
        }
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int, offset: Int): List<Post> =
            posts.filter { post -> stored.none { it.postId == post.id } }.drop(offset).take(limit)
        override fun observePredictedTags(modelId: String): Flow<Map<PostId, List<PredictedTag>>> = flowOf(emptyMap())
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = flowOf(VisionProgress(0, 0))
        override suspend fun clearVision() = stored.clear()
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
    fun `a playing post is analysed from its still and frames together, each repeated scene once`() = runBlocking {
        val library = Library(listOf(post("video")))
        val analysed = mutableListOf<Pattern>()
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { true },
            frames = { PostFrames.Read(listOf(Pattern.LEFT.bitmap(), Pattern.LEFT.bitmap(), Pattern.QUARTERS.bitmap())) },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(analysed) })

        assertFalse(backfill(tagger, library).drain())

        // Four pictures, so each tag scores the mean of its top two: the still's 0.9 and the quarters' 0.6.
        val stored = library.stored.single()
        assertEquals(listOf(PredictedTag("solo", 0.75f, PredictedTag.Kind.GENERAL)), stored.tags)
        assertEquals(listOf(Pattern.TOP, Pattern.LEFT, Pattern.QUARTERS), analysed)
    }

    @Test
    fun `while the app is on screen playing posts wait for it to leave, even mid-read, and stills carry on`() = runBlocking {
        val library = Library(listOf(post("video-1"), post("plain"), post("video-2")))
        val away = MutableStateFlow(false)
        var framesRead = 0
        var comesBack = true
        val pictures = Pictures(
            hasStill = { false },
            plays = { it.id.sourcePostId.startsWith("video") },
            frames = {
                framesRead++
                // The app comes back on screen while the first video's frames are read.
                if (comesBack) {
                    comesBack = false
                    away.value = false
                    awaitCancellation()
                }
                PostFrames.Unreadable
            },
        )
        val backfill = backfill(PictureTagger(readyStore(), library, pictures, scope), library, away)
        var scheduled = 0
        val watching = launch { backfill.whenAway { scheduled++ } }
        suspend fun leave() {
            away.value = true
            // whenAway combines two flows, which takes a few turns of this single-threaded loop.
            repeat(10) { yield() }
        }

        // Waiting frames ask for no retry: the app leaving the screen schedules the next run.
        assertFalse(backfill.drain())
        assertEquals(listOf("plain"), library.stored.map { it.postId.sourcePostId })
        assertEquals(0, framesRead)
        leave()
        assertEquals(1, scheduled)

        assertFalse(backfill.drain())
        assertEquals(listOf("plain"), library.stored.map { it.postId.sourcePostId })
        assertEquals(1, framesRead)
        leave()
        assertEquals(2, scheduled)

        assertFalse(backfill.drain())
        assertEquals(listOf("plain", "video-1", "video-2"), library.stored.map { it.postId.sourcePostId })
        leave()
        assertEquals(2, scheduled)
        watching.cancel()
    }

    @Test
    fun `leaving the screen before a run finishes still schedules the frames it left`() = runBlocking {
        val library = Library(listOf(post("video"), post("plain")))
        val away = MutableStateFlow(false)
        val pictures = Pictures(
            plays = { it.id.sourcePostId == "video" },
            // The app leaves the screen while the run is past the video, before it ends.
            still = { away.value = true; null },
        )
        val backfill = backfill(PictureTagger(readyStore(), library, pictures, scope), library, away)
        val scheduled = CompletableDeferred<Unit>()
        val watching = launch { backfill.whenAway { scheduled.complete(Unit) } }
        yield()

        backfill.drain()

        withTimeout(1_000) { scheduled.await() }
        watching.cancel()
    }

    @Test
    fun `a run cut short by heat keeps the frames an earlier run left waiting`() = runBlocking {
        val library = Library(listOf(post("video")))
        val away = MutableStateFlow(false)
        var warm = false
        val pictures = Pictures(plays = { true })
        val backfill = backfill(PictureTagger(readyStore(), library, pictures, scope), library, away, hot = { warm })
        var scheduled = 0
        val watching = launch { backfill.whenAway { scheduled++ } }

        assertFalse(backfill.drain())
        warm = true
        assertTrue(backfill.drain())
        away.value = true
        repeat(10) { yield() }

        assertEquals(1, scheduled)
        watching.cancel()
    }

    @Test
    fun `frames that cannot be read now wait for a later run, and an unreadable file leaves the still alone`() = runBlocking {
        val library = Library(listOf(post("offline"), post("broken")))
        val analysed = mutableListOf<Pattern>()
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { true },
            frames = { if (it.id.sourcePostId == "offline") PostFrames.Unreachable else PostFrames.Unreadable },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(analysed) })

        assertTrue(backfill(tagger, library).drain())

        val stored = library.stored.single()
        assertEquals("broken", stored.postId.sourcePostId)
        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), stored.tags)
        assertNull(library.stored.firstOrNull { it.postId.sourcePostId == "offline" })
    }

    private fun backfill(
        tagger: PictureTagger,
        library: Library,
        away: Flow<Boolean> = flowOf(true),
        hot: () -> Boolean = { false },
    ) = LibraryVisionBackfill(tagger, library, hot = hot, away = away)

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
