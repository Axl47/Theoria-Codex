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

sealed interface PictureHashResult {
    data class Hashed(val hash: Long) : PictureHashResult

    data object Unhashable : PictureHashResult

    data object Unavailable : PictureHashResult
}

class PreviewPictureHasher(
    private val scope: CoroutineScope,
    private val limited: (url: String) -> Boolean = { false },
    concurrentLoads: Int = CONCURRENT_LOADS,
    private val load: suspend (PostMediaCandidate, cachedOnly: Boolean) -> PictureHashResult,
) {
    private val permits = Semaphore(concurrentLoads)
    private val known = object : LinkedHashMap<String, PictureHashResult>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PictureHashResult>) = size > REMEMBERED
    }
    private val flights = ConcurrentHashMap<String, Deferred<PictureHashResult>>()

    constructor(context: Context, scope: CoroutineScope, limited: (url: String) -> Boolean) :
        this(scope, limited, load = loadWithImageLoader(context.applicationContext))

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

    suspend fun hash(post: Post): PictureHashResult {
        post.pictureHash?.let { return PictureHashResult.Hashed(it) }
        val candidate = postPreviewImageCandidate(post) ?: return PictureHashResult.Unhashable
        return flight(candidate).await()
    }

    private fun flight(candidate: PostMediaCandidate): Deferred<PictureHashResult> {
        val key = normalizeMediaUrl(candidate.source, candidate.url) ?: candidate.url
        synchronized(known) { known[key] }?.let { return CompletableDeferred(it) }
        var started: Deferred<PictureHashResult>? = null
        return flights.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = runCatchingPreservingCancellation {
                        permits.withPermit { load(candidate, limited(candidate.url)) }
                    }.getOrDefault(PictureHashResult.Unavailable)
                    if (result != PictureHashResult.Unavailable) synchronized(known) { known[key] = result }
                    result
                } finally {
                    flights.remove(key)
                }
            }.also { started = it }
        }.also { started?.start() }
    }

    companion object {
        const val LISTING_BUDGET_MS = 600L
        private const val REMEMBERED = 8_192

        internal const val DECODE_PX = 96
        private const val CONCURRENT_LOADS = 16

        private fun loadWithImageLoader(context: Context): suspend (PostMediaCandidate, Boolean) -> PictureHashResult = { candidate, cachedOnly ->
            val bitmap = loadStill(context, candidate.url, candidate.source, DECODE_PX, cachedOnly)
            if (bitmap == null) PictureHashResult.Unavailable else {
                pictureHashOf(bitmap)?.let(PictureHashResult::Hashed) ?: PictureHashResult.Unhashable
            }
        }
    }
}

suspend fun loadPreviewStill(context: Context, candidate: PostMediaCandidate, sizePx: Int): Bitmap? =
    loadStill(context, candidate.url, candidate.source, sizePx)

suspend fun pictureHashAt(context: Context, url: String, source: SourceKey, cachedOnly: Boolean = false): Long? =
    loadStill(context, url, source, PreviewPictureHasher.DECODE_PX, cachedOnly)?.let { pictureHashOf(it) }

private suspend fun pictureHashOf(bitmap: Bitmap): Long? = withContext(Dispatchers.Default) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    PerceptualHash.ofArgb(pixels, bitmap.width, bitmap.height)
}

private suspend fun loadStill(context: Context, url: String, source: SourceKey, sizePx: Int, cachedOnly: Boolean = false): Bitmap? {
    val request = MediaRequestFactory.imageRequest(
        context, url, source, crossfade = false, allowHardware = false,
        targetWidthPx = sizePx, targetHeightPx = sizePx,
    ).newBuilder()
        .decoderFactory(BitmapFactoryDecoder.Factory())
        .memoryCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(if (cachedOnly) CachePolicy.DISABLED else CachePolicy.ENABLED)
        .interceptorDispatcher(Dispatchers.Default)
        .build()
    return ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
}
