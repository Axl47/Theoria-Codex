package com.theoriacodex.app.media

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.decode.BitmapFactoryDecoder
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.SuccessResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What hashing a post's still preview gave. */
sealed interface PictureHashResult {
    data class Hashed(val hash: Long) : PictureHashResult

    /** The post has no still preview, or it is too flat to tell apart; asking again gives the same answer. */
    data object Unhashable : PictureHashResult

    /** The preview could not be loaded this time. */
    data object Unavailable : PictureHashResult
}

/**
 * Gives posts their [Post.pictureHash] from the still preview a card shows first ([postPreviewImageCandidate]), whatever
 * the source, so copies of one picture on different sites can be shown once. Loading goes through the app's image
 * loader with the card's own address, so it also fills the disk cache the card then reads; only a small first-frame
 * bitmap is decoded, off the main thread. Answers are remembered by preview address for the process's life.
 *
 * Hashes started for a listing keep running in [scope] after the listing stopped waiting for them; [onLateHash] receives
 * each post whose hash arrived too late to travel with it.
 */
class PreviewPictureHasher(
    private val scope: CoroutineScope,
    private val load: suspend (PostMediaCandidate) -> PictureHashResult,
    private val onLateHash: (Post) -> Unit = {},
) {
    private val known = object : LinkedHashMap<String, PictureHashResult>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PictureHashResult>) = size > REMEMBERED
    }
    private val flights = ConcurrentHashMap<String, Deferred<PictureHashResult>>()

    /** Hashes with the app's image loader; see [loadWithImageLoader]. */
    constructor(context: Context, scope: CoroutineScope, onLateHash: (Post) -> Unit = {}) :
        this(scope, loadWithImageLoader(context.applicationContext), onLateHash)

    /**
     * [posts] in order, each with its picture hash when its preview hashed within [budgetMs]. The wait is shared, not
     * per post: previews download in parallel, so a page waits about as long as its slowest preview, at most [budgetMs].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun annotate(posts: List<Post>, budgetMs: Long = LISTING_BUDGET_MS): List<Post> {
        val pending = posts.withIndex().mapNotNull { (index, post) ->
            if (post.pictureHash != null) null else postPreviewImageCandidate(post)?.let { index to flight(it) }
        }
        if (pending.isEmpty()) return posts
        withTimeoutOrNull(budgetMs) { pending.map { it.second }.awaitAll() }
        val hashes = arrayOfNulls<Long>(posts.size)
        pending.forEach { (index, flight) ->
            if (flight.isCompleted) {
                hashes[index] = (flight.getCompleted() as? PictureHashResult.Hashed)?.hash
            } else {
                val post = posts[index]
                scope.launch { (flight.await() as? PictureHashResult.Hashed)?.let { onLateHash(post.copy(pictureHash = it.hash)) } }
            }
        }
        return posts.mapIndexed { index, post -> hashes[index]?.let { post.copy(pictureHash = it) } ?: post }
    }

    /** [post]'s hash, however long its preview takes. */
    suspend fun hash(post: Post): PictureHashResult {
        post.pictureHash?.let { return PictureHashResult.Hashed(it) }
        val candidate = postPreviewImageCandidate(post) ?: return PictureHashResult.Unhashable
        return flight(candidate).await()
    }

    private fun flight(candidate: PostMediaCandidate): Deferred<PictureHashResult> {
        val key = normalizeMediaUrl(candidate.source, candidate.url) ?: candidate.url
        synchronized(known) { known[key] }?.let { return CompletableDeferred(it) }
        var started: Deferred<PictureHashResult>? = null
        // Started only once stored, so its removal never runs inside computeIfAbsent.
        return flights.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = runCatchingPreservingCancellation { load(candidate) }.getOrDefault(PictureHashResult.Unavailable)
                    if (result != PictureHashResult.Unavailable) synchronized(known) { known[key] = result }
                    result
                } finally {
                    flights.remove(key)
                }
            }.also { started = it }
        }.also { started?.start() }
    }

    companion object {
        /** How long a listing waits for its previews' hashes before it is shown without the missing ones. */
        const val LISTING_BUDGET_MS = 600L
        private const val REMEMBERED = 8_192

        /** Long edge to decode at; the hash averages the picture down to 32×32, so a little more is plenty. */
        private const val DECODE_PX = 96
        private const val CONCURRENT_LOADS = 16

        private fun loadWithImageLoader(context: Context): suspend (PostMediaCandidate) -> PictureHashResult {
            val permits = Semaphore(CONCURRENT_LOADS)
            return { candidate ->
                permits.withPermit {
                    val request = MediaRequestFactory.imageRequest(
                        context, candidate.url, candidate.source, crossfade = false, allowHardware = false,
                        targetWidthPx = DECODE_PX, targetHeightPx = DECODE_PX,
                    ).newBuilder()
                        // A still first frame of any format, never an animation, and no tiny bitmap in the memory cache.
                        .decoderFactory(BitmapFactoryDecoder.Factory())
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        // Coil starts requests on the main thread by default; this one has no view, so it never
                        // waits for a busy frame.
                        .interceptorDispatcher(Dispatchers.Default)
                        .build()
                    val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap == null) PictureHashResult.Unavailable else withContext(Dispatchers.Default) {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        PerceptualHash.ofArgb(pixels, bitmap.width, bitmap.height)
                            ?.let(PictureHashResult::Hashed) ?: PictureHashResult.Unhashable
                    }
                }
            }
        }
    }
}
