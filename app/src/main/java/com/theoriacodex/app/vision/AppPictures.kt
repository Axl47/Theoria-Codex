package com.theoriacodex.app.vision

import android.content.Context
import android.os.PowerManager
import com.theoriacodex.app.BuildConfig
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.theoriacodex.app.media.LibraryPictureBackfill
import com.theoriacodex.app.media.PostMotion
import com.theoriacodex.app.media.loadPreviewStill
import com.theoriacodex.app.media.postMotion
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.VideoFrameSample
import com.theoriacodex.app.viewer.VideoPlaybackInfrastructure
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * The Library's picture upkeep and the opt-in picture tagger, as the app holds them: [model] is the tagger's download,
 * [tagger] what it predicts, and [indexLibrary] the work the charging-only background job does.
 */
class AppPictures(
    val model: VisualModelStore,
    val tagger: PictureTagger,
    private val hashes: LibraryPictureBackfill,
    val tags: LibraryVisionBackfill,
    /** How many Library posts the current model has analysed, out of how many. */
    val progress: Flow<VisionProgress>,
) {
    /** Hashes, then tags, the Library's pictures that lack either; returns whether tagging has more left. */
    suspend fun indexLibrary(): Boolean {
        hashes.drain()
        return tags.drain()
    }
}

/**
 * Builds [AppPictures]: the model lives in no-backup storage, since it is large and downloads again, and its files come
 * from [BuildConfig.VISION_MODEL_BASE_URL]. Deleting it removes every stored prediction too. [hashes] is the Library's
 * picture hashing, which runs whether or not the model is there. [resolvePost] looks a post up again at its source, for
 * a video whose saved address expired.
 */
fun appPictures(
    context: Context,
    client: OkHttpClient,
    signatures: VisualSignatureRepository,
    hashes: LibraryPictureBackfill,
    ugoira: PixivUgoiraClient,
    resolvePost: suspend (PostId) -> Post?,
    scope: CoroutineScope,
): AppPictures {
    val app = context.applicationContext
    lateinit var tagger: PictureTagger
    val model = VisualModelStore(
        VisualModelSpec.wdVitTaggerV3(BuildConfig.VISION_MODEL_BASE_URL), File(app.noBackupFilesDir, "models"), client, scope,
    ) { tagger.forget() }
    tagger = PictureTagger(model, signatures, AppPictureSource(app, ugoira, resolvePost), scope)
    val power = app.getSystemService(PowerManager::class.java)
    val tags = LibraryVisionBackfill(
        tagger,
        signatures,
        hot = { power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE },
        quiet = {
            withContext(Dispatchers.Main.immediate) {
                !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !app.videoPlaybackInfrastructure().hasActivePlayer
            }
        },
    )
    return AppPictures(model, tagger, hashes, tags, signatures.observeVisionProgress(model.id))
}

/**
 * The tagger's pictures as the app holds them: stills through the image loader, from the disk cache cards fill; video
 * frames through the shared video cache ([VideoPlaybackInfrastructure.sampleFrames]); Pixiv animation frames from their
 * archive. A video whose server refuses its saved address is looked up again once, since addresses expire; when that
 * fails too, its still is analysed alone.
 */
private class AppPictureSource(
    private val context: Context,
    private val ugoira: PixivUgoiraClient,
    private val resolvePost: suspend (PostId) -> Post?,
) : PictureSource {
    override fun hasStill(post: Post): Boolean = postPreviewImageCandidate(post) != null

    override suspend fun still(post: Post): Bitmap? = postPreviewImageCandidate(post)?.let { loadPreviewStill(context, it, INPUT_PX) }

    override fun plays(post: Post): Boolean = postMotion(post) != null

    override suspend fun frames(post: Post): PostFrames = when (val motion = postMotion(post)) {
        null -> PostFrames.Unreadable
        is PostMotion.Ugoira -> runCatchingPreservingCancellation { ugoira.sampleFrames(motion.postId.sourcePostId, FRAMES, INPUT_PX) }
            .fold({ if (it.isEmpty()) PostFrames.Unreadable else PostFrames.Read(it) }, ::failed)
        is PostMotion.Video -> when (val sample = sample(motion)) {
            VideoFrameSample.Refused -> refreshed(post)
            else -> sample.asFrames()
        }
    }

    /** A refused video's post as its source lists it now, sampled again; refused again, nothing beyond the still. */
    private suspend fun refreshed(post: Post): PostFrames {
        val fresh = runCatchingPreservingCancellation { resolvePost(post.id) }.getOrElse { return failed(it) }
        val motion = fresh?.let(::postMotion) as? PostMotion.Video ?: return PostFrames.Unreadable
        return sample(motion).asFrames()
    }

    private suspend fun sample(video: PostMotion.Video): VideoFrameSample =
        context.videoPlaybackInfrastructure().sampleFrames(video.location, video.headers, FRAMES, INPUT_PX, MAX_VIDEO_NETWORK_BYTES)

    private fun VideoFrameSample.asFrames(): PostFrames = when (this) {
        is VideoFrameSample.Frames -> PostFrames.Read(frames)
        VideoFrameSample.Unreachable -> PostFrames.Unreachable
        VideoFrameSample.Refused, VideoFrameSample.Unreadable -> PostFrames.Unreadable
    }

    /** A network failure may pass, so a later run tries again; any other failure leaves the still alone. */
    private fun failed(error: Throwable): PostFrames =
        if (generateSequence(error, Throwable::cause).any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException }) {
            PostFrames.Unreachable
        } else {
            PostFrames.Unreadable
        }
}

/** The model reads 448 px squares; decoding the preview larger only costs memory. */
private const val INPUT_PX = 448

/** Frames read from across a playing post, beside its still. */
private const val FRAMES = 6

/** A light rendition's index and six keyframes usually take well under this. */
private const val MAX_VIDEO_NETWORK_BYTES = 6L * 1024 * 1024
