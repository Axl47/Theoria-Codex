package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.data.repository.MAX_FRAME_ATTEMPTS
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionCommit
import com.theoriacodex.data.repository.VisionFrames
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PictureLook
import com.theoriacodex.domain.vision.PredictedTag
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

@OptIn(VisionCommit::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryVisionBackfillTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Library(initial: List<Post>) : VisualSignatureRepository {
        val posts = initial.toMutableList()
        val stored = mutableListOf<PictureVision>()
        private val total = MutableStateFlow(posts.size)
        var writeGate: CompletableDeferred<Unit>? = null
        val writing = CompletableDeferred<Unit>()
        private val attempts = HashMap<PostId, Int>()
        private val changes = MutableStateFlow(0)
        var tagReads = 0
        var unwantWrites = 0
        private var awaitingReads = 0
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = flowOf(emptyMap())
        override suspend fun putHashes(hashes: Map<PostId, Long?>) = Unit
        val failed = LinkedHashSet<PostId>()
        override suspend fun failPreviews(postIds: Collection<PostId>) {
            failed += postIds
        }
        override suspend fun libraryPostsWithoutSignature(limit: Int, skip: Int): List<Post> = emptyList()
        override suspend fun putVision(entries: List<PictureVision>) {
            writeGate?.let { gate ->
                writing.complete(Unit)
                gate.await()
            }
            for (entry in entries) {
                val at = stored.indexOfFirst { it.postId == entry.postId }
                if (at >= 0) stored[at] = entry else stored += entry
                attempts.remove(entry.postId)
            }
            changes.value += 1
        }
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int, skip: Int): List<Post> =
            posts.filter { post -> post.id !in failed && stored.none { it.postId == post.id } }.drop(skip).take(limit)
        override suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int): List<Post> {
            check(++awaitingReads <= MAX_AWAITING_READS) { "frames awaited $awaitingReads times: a pass stopped settling posts" }
            return posts.filter { it.id in signaturesAwaitingFrames() }.drop(skip).take(limit)
        }
        override suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean) {
            attempts[postId] = (attempts[postId] ?: 0) + if (permanently) MAX_FRAME_ATTEMPTS else 1
        }
        override suspend fun unwantFrames(postIds: List<PostId>, modelId: String) {
            unwantWrites += 1
            stored.replaceAll { if (it.postId in postIds) it.copy(frames = VisionFrames.NONE) else it }
        }
        override fun observeVisionChanges(): Flow<Unit> = changes.map { }
        override suspend fun predictedTags(modelId: String): Map<PostId, List<PredictedTag>> {
            tagReads += 1
            return stored.mapNotNull { vision -> vision.tags?.let { vision.postId to it } }.toMap()
        }
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = flowOf(VisionProgress(0, 0))
        override fun observeLibraryTotal(): Flow<Int> = total
        override suspend fun clearVision() = stored.clear()

        fun vision(id: String) = stored.single { it.postId.sourcePostId == id }

        fun keep(post: Post) {
            posts += post
            total.value = posts.size
        }

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

    private fun readyStore(onDeleted: suspend () -> Unit = {}): VisualModelStore {
        val folders = VisualModelStore(spec, folder.root, OkHttpClient(), scope) { }
        folders.modelFile.parentFile!!.mkdirs()
        folders.modelFile.writeBytes(model)
        folders.vocabularyFile.writeBytes(words)
        return VisualModelStore(spec, folder.root, OkHttpClient(), scope, onDeleted)
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.DANBOORU, id), preview = ImageRef("https://cdn.test/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )

    @Test
    fun `stored tags are read once the Library settles, not once per write (ADR 0097)`() = runTest {
        val library = Library(List(10) { post("p$it") })
        PictureTagger(readyStore(), library, Pictures(), backgroundScope)
        runCurrent()
        assertEquals(1, library.tagReads)

        repeat(10) { index ->
            library.putVision(listOf(PictureVision(post("p$index").id, spec.id, null, emptyList())))
            advanceTimeBy(100)
        }
        assertEquals(1, library.tagReads)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, library.tagReads)
    }

    @Test
    fun `previews that fail to load are stamped and left for a later day, so the older Library is still indexed (ADR 0094, 0097)`() = runBlocking {
        val dead = List(40) { post("dead-$it") }
        val older = List(3) { post("no-still-$it") }
        val library = Library(dead + older)
        val store = readyStore()
        assertEquals(ModelState.Ready, store.states.value)
        val tagger = PictureTagger(store, library, Pictures(hasStill = { it.id.sourcePostId.startsWith("dead") }), scope)

        assertFalse(backfill(tagger, library).drain())

        assertEquals(older.map(Post::id), library.stored.map(PictureVision::postId))
        assertEquals(dead.map(Post::id).toSet(), library.failed)
    }

    @Test
    fun `posts on a host that is holding requests off are not loaded, and a later run tries them again (ADR 0094)`() = runBlocking {
        val held = List(40) { post("held-$it") }
        val open = List(3) { post("open-$it") }
        val library = Library(held + open)
        val loaded = mutableListOf<String>()
        val pictures = Pictures(still = { loaded += it.id.sourcePostId; Pattern.TOP.bitmap() })
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })

        assertTrue(LibraryVisionBackfill(tagger, library, hot = { false }) { !it.id.sourcePostId.startsWith("held") }.drain())

        assertEquals(open.map { it.id.sourcePostId }, loaded)
        assertEquals(open.map(Post::id), library.stored.map(PictureVision::postId))
    }

    @Test
    fun `while the app runs, analyses awaiting frames whose post does not play stop waiting (ADR 0094, 0097)`() = runBlocking {
        val videos = List(300) { post("video-$it") }
        val library = Library(videos + post("picture-a") + post("picture-b"))
        library.putVision(library.posts.map { PictureVision(it.id, spec.id, null, emptyList(), VisionFrames.WANTED) })
        val tagger = PictureTagger(readyStore(), library, Pictures(plays = { it.id.sourcePostId.startsWith("video") }), scope)

        val running = scope.launch { backfill(tagger, library).writeThrough() }
        assertTrue("the write-through stopped instead of waiting for saves", running.isActive)
        running.cancel()

        assertEquals(videos.map(Post::id), library.signaturesAwaitingFrames())
        assertEquals(1, library.unwantWrites)
    }

    @Test
    fun `a model that will not load asks for another run while it is installed, and none once it is gone (ADR 0094)`() = runBlocking {
        val library = Library(listOf(post("a")))
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
    fun `a playing post is stored from its still first, then upgraded with its frames, each repeated scene once (ADR 0094)`() = runBlocking {
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

        val upgraded = library.vision("video")
        assertEquals(VisionFrames.READ, upgraded.frames)
        assertEquals(listOf(PredictedTag("solo", 0.75f, PredictedTag.Kind.GENERAL)), upgraded.tags)
        assertEquals(listOf(Pattern.TOP, Pattern.LEFT, Pattern.QUARTERS), analysed)
        assertTrue(library.signaturesAwaitingFrames().isEmpty())
    }

    @Test
    fun `frames that cannot be read leave the still analysis standing, tried once a run and a few times at most (ADR 0094, 0097)`() = runBlocking {
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
    fun `posts failing to be reached one after another stop the run and count once, and posts that stopped playing are set aside (ADR 0094)`() = runBlocking {
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

        assertTrue(backfill.drainFrames())
        assertEquals(3, read)
        assertEquals(VisionFrames.NONE, library.vision("gif").frames)
        assertEquals(listOf("a", "b", "c", "d").map { post(it).id }, library.signaturesAwaitingFrames())

        repeat(MAX_FRAME_ATTEMPTS - 1) { backfill.drainFrames() }
        assertEquals(listOf("b", "c", "d").map { post(it).id }, library.signaturesAwaitingFrames())

        val mixed = Library(listOf("u1", "ok", "u2", "u3").map(::post))
        val mixedPictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { true },
            frames = { if (it.id.sourcePostId == "ok") PostFrames.Read(listOf(Pattern.LEFT.bitmap())) else PostFrames.Unreachable },
        )
        val mixedBackfill = backfill(PictureTagger(readyStore(), mixed, mixedPictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) }), mixed)
        mixedBackfill.drain()
        assertFalse(mixedBackfill.drainFrames())
    }

    @Test
    fun `a device running warm stops the frame upgrade and asks for another run (ADR 0094)`() = runBlocking {
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
    fun `what the Library stored for a post wins over this session's look at its still (ADR 0097)`() = runBlocking {
        val library = Library(listOf(post("video")))
        val pictures = Pictures(
            still = { Pattern.LEFT.bitmap() },
            plays = { true },
            frames = { PostFrames.Read(listOf(Pattern.TOP.bitmap(), Pattern.TOP.bitmap())) },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val video = post("video")
        tagger.analyze(video)
        assertEquals(emptyList<PredictedTag>(), tagger.known(video.id))

        library.putVision(listOf(PictureVision(video.id, tagger.modelId, byteArrayOf(1), listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)))))
        withTimeout(10_000) { tagger.library.first { it[video.id] != null } }

        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), tagger.known(video.id))
        assertEquals(PictureLook.Seen(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL))), tagger.look(video))
    }

    @Test
    fun `look remembers only a seen picture, so an unreachable one is asked again (ADR 0005)`() = runBlocking {
        var reachable = false
        val pictures = Pictures(still = { if (reachable) Pattern.TOP.bitmap() else null })
        val tagger = PictureTagger(readyStore(), Library(emptyList()), pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val late = post("late")

        assertEquals(PictureLook.Unseen, tagger.look(late))
        reachable = true
        assertEquals(PictureLook.Seen(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL))), tagger.look(late))
    }

    @Test
    fun `a picture analysed this session is known but never reaches the library flow (ADR 0005)`() = runTest {
        val pictures = Pictures(still = { Pattern.TOP.bitmap() })
        val tagger = PictureTagger(readyStore(), Library(emptyList()), pictures, backgroundScope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val seen = post("seen")
        val settled = tagger.library.first { it.baseRates.isNotEmpty() }

        tagger.analyze(seen)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), tagger.known(seen.id))
        assertEquals(settled, tagger.library.value)
    }

    @Test
    fun `known with a ready model answers null for an unanalysed picture without reading or analysing it (ADR 0005)`() = runBlocking {
        var reads = 0
        val analysed = mutableListOf<Pattern>()
        val pictures = Pictures(still = { reads++; Pattern.TOP.bitmap() })
        val tagger = PictureTagger(readyStore(), Library(emptyList()), pictures, scope, openModel = { _, _ -> PatternModel(analysed) })
        withTimeout(5_000) { tagger.ready.first { it } }

        val known = tagger.known(post("fresh").id)

        assertEquals(listOf(null, 0, emptyList<Pattern>()), listOf(known, reads, analysed))
    }

    @Test
    fun `a device running warm stops the picture pass before its next batch and asks for another run (ADR 0094)`() = runBlocking {
        val library = Library(listOf(post("a")))
        val tagger = PictureTagger(readyStore(), library, Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val many = Library(List(9) { post("p$it") })
        val warming = PictureTagger(readyStore(), many, Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> PatternModel(mutableListOf()) })

        assertTrue(LibraryVisionBackfill(tagger, library, hot = { true }).drain())
        assertTrue(LibraryVisionBackfill(warming, many, hot = { many.stored.isNotEmpty() }).drain())

        assertEquals(emptyList<PictureVision>(), library.stored)
        assertEquals(8, many.stored.size)
    }

    @Test
    fun `stopping the frame upgrade keeps the posts already upgraded and loses only the one under way (ADR 0094)`() = runBlocking {
        val library = Library(listOf(post("first"), post("second")))
        val reading = CompletableDeferred<Unit>()
        val pictures = Pictures(
            still = { Pattern.TOP.bitmap() },
            plays = { true },
            frames = {
                if (it.id.sourcePostId == "first") PostFrames.Read(listOf(Pattern.LEFT.bitmap())) else {
                    reading.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val backfill = backfill(tagger, library)
        backfill.drain()
        val running = scope.launch { backfill.drainFrames() }
        withTimeout(5_000) { reading.await() }

        running.cancelAndJoin()

        assertEquals(listOf(VisionFrames.READ, VisionFrames.WANTED), listOf("first", "second").map { library.vision(it).frames })
        assertEquals(listOf(PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL)), library.vision("second").tags)
        assertEquals(listOf(post("second").id), library.signaturesAwaitingFrames())
    }

    @Test
    fun `a picture the session already analysed is stored once its post is kept, without reading it again (ADR 0094)`() = runBlocking {
        val library = Library(listOf(post("unseen")))
        var stills = 0
        val pictures = Pictures(still = { stills++; Pattern.TOP.bitmap() })
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        tagger.analyze(post("liked"))

        val running = scope.launch { backfill(tagger, library).writeThrough() }
        assertEquals(emptyList<PictureVision>(), library.stored)
        library.keep(post("liked"))
        assertTrue("the write-through stopped instead of waiting for saves", running.isActive)
        running.cancel()

        assertEquals(listOf(post("liked").id), library.stored.map(PictureVision::postId))
        assertEquals(1, stills)
    }

    @Test
    fun `a delete that lands during a write waits for it, then forgets what it wrote (ADR 0097)`() = runBlocking {
        lateinit var tagger: PictureTagger
        val library = Library(listOf(post("a")))
        val store = readyStore { tagger.forget() }
        tagger = PictureTagger(store, library, Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        val result = tagger.analyze(post("a"), remember = false)
        library.writeGate = CompletableDeferred()
        val writing = scope.async { tagger.store(mapOf(post("a") to result)) }
        withTimeout(5_000) { library.writing.await() }

        val deleting = scope.launch { store.delete() }
        withTimeout(5_000) { store.states.first { it == ModelState.Absent } }
        delay(200)
        library.writeGate!!.complete(Unit)
        writing.await()
        withTimeout(5_000) { deleting.join() }

        assertEquals(emptyList<PictureVision>(), library.stored)
    }

    @Test
    fun `once the model is deleted nothing is stored or remembered, even a picture read before the delete (ADR 0097)`() = runBlocking {
        val library = Library(listOf(post("a")))
        val store = readyStore()
        val deleting = object : PictureModel {
            override fun analyze(picture: Bitmap): PictureAnalysis {
                runBlocking { store.delete() }
                return PictureAnalysis(embedding = floatArrayOf(1f, 0f), scores = floatArrayOf(0.9f))
            }

            override fun close() = Unit
        }
        val tagger = PictureTagger(store, library, Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> deleting })
        val analysed = PictureResult.Analysed(emptyList(), byteArrayOf(1))

        assertEquals(PictureResult.Unavailable, tagger.analyze(post("a")))
        assertNull(tagger.remembered(post("a").id))
        assertFalse(tagger.store(mapOf(post("a") to analysed)))
        assertFalse(tagger.storeFrames(post("a").id, analysed))
        assertEquals(emptyList<PictureVision>(), library.stored)

        val reinstalled = readyStore()
        val deletingAgain = object : PictureModel {
            override fun analyze(picture: Bitmap): PictureAnalysis {
                runBlocking { reinstalled.delete() }
                error("the model went while it read the picture")
            }

            override fun close() = Unit
        }
        val indexing = PictureTagger(reinstalled, library, Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> deletingAgain })
        assertFalse(backfill(indexing, library).drain())
    }

    @Test
    fun `only a session look at a still is remembered, not library indexing, a failed load or a frame read (ADR 0097)`() = runBlocking {
        val library = Library(listOf(post("still"), post("video")))
        val pictures = Pictures(
            still = { if (it.id.sourcePostId == "offline") null else Pattern.TOP.bitmap() },
            plays = { it.id.sourcePostId == "video" },
            frames = { PostFrames.Read(listOf(Pattern.LEFT.bitmap())) },
        )
        val tagger = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })

        assertFalse(backfill(tagger, library).drain())
        assertEquals(2, library.stored.size)
        assertNull(tagger.remembered(post("still").id))
        assertNull(tagger.remembered(post("video").id))
        assertEquals(PictureResult.Unreachable, tagger.analyze(post("offline")))
        assertNull(tagger.remembered(post("offline").id))
        tagger.analyzeFrames(post("video"))
        assertNull(tagger.remembered(post("video").id))
        assertTrue(tagger.analyze(post("still")) is PictureResult.Analysed)
        assertTrue(tagger.remembered(post("still").id) is PictureResult.Analysed)

        val unloadable = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> error("out of memory") })
        assertEquals(PictureResult.Unavailable, unloadable.analyze(post("still")))
        assertNull(unloadable.remembered(post("still").id))
    }

    @Test
    fun `a model that cannot load leaves a picture Unavailable and one that fails on it makes it Unanalysable (ADR 0097)`() = runBlocking {
        val library = Library(listOf(post("a")))
        val pictures = Pictures(still = { Pattern.TOP.bitmap() })
        val unloadable = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> error("out of memory") })
        val failing = object : PictureModel {
            override fun analyze(picture: Bitmap): PictureAnalysis = error("this picture breaks the model")

            override fun close() = Unit
        }
        val broken = PictureTagger(readyStore(), library, pictures, scope, openModel = { _, _ -> failing })

        assertEquals(PictureResult.Unavailable, unloadable.analyze(post("a"), remember = false))
        assertEquals(PictureResult.Unanalysable, broken.analyze(post("a"), remember = false))
    }

    @Test
    fun `deleting the model forgets what it predicted, stored or remembered (ADR 0097)`() = runBlocking {
        lateinit var tagger: PictureTagger
        val library = Library(listOf(post("kept"), post("seen")))
        val store = readyStore { tagger.forget() }
        val pictures = Pictures(hasStill = { it.id.sourcePostId != "blank" }, still = { Pattern.TOP.bitmap() })
        tagger = PictureTagger(store, library, pictures, scope, openModel = { _, _ -> PatternModel(mutableListOf()) })
        tagger.store(mapOf(post("kept") to tagger.analyze(post("kept"), remember = false)))
        tagger.analyze(post("seen"))
        tagger.analyze(post("blank"))
        assertEquals(listOf(post("kept").id), library.stored.map(PictureVision::postId))
        assertEquals(PictureResult.Unanalysable, tagger.remembered(post("blank").id))

        store.delete()

        assertEquals(emptyList<PictureVision>(), library.stored)
        assertEquals(listOf(null, null), listOf("seen", "blank").map { tagger.remembered(post(it).id) })
    }

    @Test
    fun `the session remembers only its most recent results (ADR 0097)`() = runBlocking {
        val tagger = PictureTagger(readyStore(), Library(emptyList()), Pictures(hasStill = { false }), scope)
        val posts = List(SessionAnalyses.LIMIT + 1) { post("p$it") }

        posts.forEach { tagger.analyze(it) }

        assertNull(tagger.remembered(posts.first().id))
        assertEquals(PictureResult.Unanalysable, tagger.remembered(posts[1].id))
        assertEquals(PictureResult.Unanalysable, tagger.remembered(posts.last().id))
    }

    @Test
    fun `a malformed vocabulary leaves the store Ready and the picture Unavailable for a later try (ADR 0097, 0101)`() = runBlocking {
        val garbage = "garbage".toByteArray()
        val malformed = spec.copy(vocabulary = ModelFile("tags.csv", garbage.size.toLong(), sha(garbage)))
        val placed = VisualModelStore(malformed, folder.root, OkHttpClient(), scope) { }
        placed.modelFile.parentFile!!.mkdirs()
        placed.modelFile.writeBytes(model)
        placed.vocabularyFile.writeBytes(garbage)
        val store = VisualModelStore(malformed, folder.root, OkHttpClient(), scope) { }
        val tagger = PictureTagger(store, Library(listOf(post("a"))), Pictures(still = { Pattern.TOP.bitmap() }), scope, openModel = { _, _ -> PatternModel(mutableListOf()) })

        assertEquals(PictureResult.Unavailable, tagger.analyze(post("a")))
        assertNull(tagger.remembered(post("a").id))
        assertEquals(ModelState.Ready, store.states.value)
    }

    private fun backfill(tagger: PictureTagger, library: Library) = LibraryVisionBackfill(tagger, library, hot = { false })

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

private const val MAX_AWAITING_READS = 1_000
