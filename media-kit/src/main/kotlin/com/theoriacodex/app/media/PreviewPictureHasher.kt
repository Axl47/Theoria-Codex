package com.theoriacodex.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.decode.BitmapFactoryDecoder
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.SuccessResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Hashes started for a listing keep running in [scope] after the listing stopped waiting for them, so the next listing
 * of those posts has them at once. A late hash is not applied to the listing that missed it: a card on screen never
 * collapses or hides because of it.
 */
class PreviewPictureHasher(
    private val scope: CoroutineScope,
    private val load: suspend (PostMediaCandidate) -> PictureHashResult,
) {
    private val known = object : LinkedHashMap<String, PictureHashResult>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PictureHashResult>) = size > REMEMBERED
    }
    private val flights = ConcurrentHashMap<String, Deferred<PictureHashResult>>()

    /** Hashes with the app's image loader; see [loadWithImageLoader]. */
    constructor(context: Context, scope: CoroutineScope) : this(scope, loadWithImageLoader(context.applicationContext))

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
            if (flight.isCompleted) hashes[index] = (flight.getCompleted() as? PictureHashResult.Hashed)?.hash
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
        internal const val DECODE_PX = 96
        private const val CONCURRENT_LOADS = 16

        private fun loadWithImageLoader(context: Context): suspend (PostMediaCandidate) -> PictureHashResult {
            val permits = Semaphore(CONCURRENT_LOADS)
            return { candidate ->
                permits.withPermit {
                    val bitmap = loadPreviewStill(context, candidate, DECODE_PX)
                    if (bitmap == null) PictureHashResult.Unavailable else {
                        pictureHashOf(bitmap)?.let(PictureHashResult::Hashed) ?: PictureHashResult.Unhashable
                    }
                }
            }
        }
    }
}

/**
 * [candidate]'s still first frame, decoded to about [sizePx] on its long edge, or null when it could not be loaded. It
 * goes through the app's image loader with the card's own address, so it reads and fills the disk cache cards use, and
 * keeps the bitmap out of the memory cache. Picture hashing and the picture tagger both read previews this way.
 */
suspend fun loadPreviewStill(context: Context, candidate: PostMediaCandidate, sizePx: Int): Bitmap? =
    loadStill(context, candidate.url, candidate.source, sizePx)

/**
 * The picture hash of the image at [url] (an address or a file) from [source], or null when it could not be loaded or
 * is too flat to tell apart. Loads as [loadPreviewStill] does.
 */
suspend fun pictureHashAt(context: Context, url: String, source: SourceKey): Long? =
    loadStill(context, url, source, PreviewPictureHasher.DECODE_PX)?.let { pictureHashOf(it) }

private suspend fun pictureHashOf(bitmap: Bitmap): Long? = withContext(Dispatchers.Default) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    PerceptualHash.ofArgb(pixels, bitmap.width, bitmap.height)
}

private suspend fun loadStill(context: Context, url: String, source: SourceKey, sizePx: Int): Bitmap? {
    val request = MediaRequestFactory.imageRequest(
        context, url, source, crossfade = false, allowHardware = false,
        targetWidthPx = sizePx, targetHeightPx = sizePx,
    ).newBuilder()
        // A still first frame of any format, never an animation, and no bitmap in the memory cache.
        .decoderFactory(BitmapFactoryDecoder.Factory())
        .memoryCachePolicy(CachePolicy.DISABLED)
        // Coil starts requests on the main thread by default; this one has no view, so it never waits for a busy frame.
        .interceptorDispatcher(Dispatchers.Default)
        .build()
    return ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
}
