package com.theoriacodex.app.vision

import android.content.Context
import android.os.PowerManager
import com.theoriacodex.app.BuildConfig
import android.graphics.Bitmap
import com.theoriacodex.app.media.LibraryPictureBackfill
import com.theoriacodex.app.media.PostMotion
import com.theoriacodex.app.media.awaitsVideoStream
import com.theoriacodex.app.media.loadPreviewStill
import com.theoriacodex.app.media.postMotion
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.viewer.FrameSample
import com.theoriacodex.app.viewer.NetworkBudget
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.frameSample
import com.theoriacodex.app.viewer.VideoPlaybackInfrastructure
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

class AppPictures(
    val model: VisualModelStore,
    val tagger: PictureTagger,
    private val hashes: LibraryPictureBackfill,
    val tags: LibraryVisionBackfill,
    val progress: Flow<VisionProgress>,
) {
    suspend fun indexLibrary(): Boolean {
        hashes.drain()
        return tags.drain()
    }

    suspend fun readFrames(): Boolean = tags.drainFrames()
}

fun appPictures(
    context: Context,
    client: OkHttpClient,
    signatures: VisualSignatureRepository,
    hashes: LibraryPictureBackfill,
    ugoira: PixivUgoiraClient,
    offlineCopy: suspend (PostId) -> Post?,
    resolvePost: suspend (PostId) -> Post?,
    scope: CoroutineScope,
): AppPictures {
    val app = context.applicationContext
    lateinit var tagger: PictureTagger
    val model = VisualModelStore(
        VisualModelSpec.wdVitTaggerV3(BuildConfig.VISION_MODEL_BASE_URL), File(app.noBackupFilesDir, "models"), client, scope,
    ) { tagger.forget() }
    tagger = PictureTagger(model, signatures, AppPictureSource(app, ugoira, offlineCopy, resolvePost), scope)
    val power = app.getSystemService(PowerManager::class.java)
    val tags = LibraryVisionBackfill(tagger, signatures, hot = { power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE })
    return AppPictures(model, tagger, hashes, tags, signatures.observeVisionProgress(model.id))
}

private class AppPictureSource(
    private val context: Context,
    private val ugoira: PixivUgoiraClient,
    private val offlineCopy: suspend (PostId) -> Post?,
    private val resolvePost: suspend (PostId) -> Post?,
) : PictureSource {
    override fun hasStill(post: Post): Boolean = postPreviewImageCandidate(post) != null

    override suspend fun still(post: Post): Bitmap? = postPreviewImageCandidate(post)?.let { loadPreviewStill(context, it, INPUT_PX) }

    override fun plays(post: Post): Boolean = postMotion(post) != null || post.awaitsVideoStream

    override suspend fun frames(post: Post): PostFrames {
        val budget = NetworkBudget(MAX_POST_NETWORK_BYTES)
        val offline = runCatchingPreservingCancellation { offlineCopy(post.id) }.getOrNull()
        val motion = offline?.let(::postMotion) ?: postMotion(post) ?: return refreshed(post, budget)
        val sample = sample(motion, budget)
        return if (sample == FrameSample.Refused && motion is PostMotion.Video) refreshed(post, budget) else sample.asFrames()
    }

    private suspend fun refreshed(post: Post, budget: NetworkBudget): PostFrames {
        val fresh = runCatchingPreservingCancellation { resolvePost(post.id) }
            .getOrElse { return frameSample(emptyList(), it).asFrames() }
        val motion = fresh?.let(::postMotion) ?: return PostFrames.Unreadable
        return sample(motion, budget).asFrames()
    }

    private suspend fun sample(motion: PostMotion, budget: NetworkBudget): FrameSample = runCatchingPreservingCancellation {
        when (motion) {
            is PostMotion.Ugoira -> ugoira.sampleFrames(motion.postId.sourcePostId, FRAMES, INPUT_PX, budget)
            is PostMotion.Video ->
                context.videoPlaybackInfrastructure().sampleFrames(motion.location, motion.headers, FRAMES, INPUT_PX, budget)
        }
    }.getOrElse { frameSample(emptyList(), it) }

    private fun FrameSample.asFrames(): PostFrames = when (this) {
        is FrameSample.Frames -> PostFrames.Read(frames)
        FrameSample.Unreachable -> PostFrames.Unreachable
        FrameSample.Refused, FrameSample.Unreadable -> PostFrames.Unreadable
    }
}

private const val INPUT_PX = 448

private const val FRAMES = 6

private const val MAX_POST_NETWORK_BYTES = 6L * 1024 * 1024
