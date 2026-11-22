package com.theoriacodex.app.vision

import android.content.Context
import android.os.PowerManager
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.media.LibraryPictureBackfill
import com.theoriacodex.app.media.loadPreviewStill
import com.theoriacodex.app.media.postPreviewImageCandidate
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
 * picture hashing, which runs whether or not the model is there.
 */
fun appPictures(
    context: Context,
    client: OkHttpClient,
    signatures: VisualSignatureRepository,
    hashes: LibraryPictureBackfill,
    scope: CoroutineScope,
): AppPictures {
    val app = context.applicationContext
    lateinit var tagger: PictureTagger
    val model = VisualModelStore(
        VisualModelSpec.wdVitTaggerV3(BuildConfig.VISION_MODEL_BASE_URL), File(app.noBackupFilesDir, "models"), client, scope,
    ) {
        tagger.forget()
        signatures.clearVision()
    }
    tagger = PictureTagger(
        model,
        signatures,
        hasStill = { post -> postPreviewImageCandidate(post) != null },
        loadStill = { post -> postPreviewImageCandidate(post)?.let { loadPreviewStill(app, it, INPUT_PX) } },
        scope,
    )
    val power = app.getSystemService(PowerManager::class.java)
    val tags = LibraryVisionBackfill(tagger, signatures) { power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE }
    return AppPictures(model, tagger, hashes, tags, signatures.observeVisionProgress(model.id))
}

/** The model reads 448 px squares; decoding the preview larger only costs memory. */
private const val INPUT_PX = 448
