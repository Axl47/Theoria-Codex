package com.theoriacodex.app.acceptance

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.recommend.ForYouInterests
import com.theoriacodex.app.recommend.pictureAffinity
import com.theoriacodex.app.triage.hiddenBy
import com.theoriacodex.app.vision.AppPictures
import com.theoriacodex.app.vision.ModelState
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PredictedTagIndex
import com.theoriacodex.domain.vision.PredictedTagUse
import com.theoriacodex.domain.vision.tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Installed only in the acceptance APK: drives the picture tagger through the app's own container, one phase per
 * launch, and logs `PASS:<phase>:<numbers>` or `FAIL:<phase>:<reason>` under [TAG]. Posts are fixtures whose pictures
 * (synthetic line drawings) and model files come from the host's loopback server at [BuildConfig.VISION_MODEL_BASE_URL].
 * scripts/verify_vision_acceptance_device.sh runs the phases in order, switching charging between them.
 */
class VisionAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = intent.getStringExtra("phase").orEmpty()
        val status = TextView(this).also { setContentView(it) }
        status.text = "Vision acceptance: $phase"
        lifecycleScope.launch {
            val outcome = runCatchingPreservingCancellation {
                val container = (application as TheoriaAppContainerOwner).awaitAppContainer()
                withTimeout(PHASE_TIMEOUT_MS) { VisionPhases(container, checkNotNull(container.pictures)).run(phase) }
            }
            outcome.onSuccess { Log.i(TAG, "PASS:$phase:$it") }
                .onFailure { Log.e(TAG, "FAIL:$phase:${it.javaClass.simpleName}:${it.message}") }
            status.text = "Vision acceptance: $phase ${if (outcome.isSuccess) "passed" else "failed"}"
        }
    }
}

private class VisionPhases(private val container: TheoriaAppContainer, private val pictures: AppPictures) {
    private val library = List(FIXTURES) { fixturePost("vision-$it", it) }

    // Not in the Library and without provider tags, like a For You candidate a source left untagged.
    private val untagged = fixturePost("vision-untagged", FIXTURES)

    suspend fun run(phase: String): String = when (phase) {
        "download" -> download()
        "seed" -> seed()
        "idle" -> idle()
        "check" -> check()
        "delete" -> delete()
        else -> error("unknown phase")
    }

    private suspend fun download(): String {
        check(pictures.model.states.value == ModelState.Absent) { "expected Absent, was ${pictures.model.states.value}" }
        val started = SystemClock.elapsedRealtime()
        pictures.model.download()
        val state = pictures.model.states.first { it == ModelState.Ready || it is ModelState.Failed }
        check(state == ModelState.Ready) { "download ended $state" }
        val bytes = pictures.model.modelFile.length() + pictures.model.vocabularyFile.length()
        check(bytes == pictures.model.totalBytes)
        return "bytes=$bytes ms=${SystemClock.elapsedRealtime() - started}"
    }

    private suspend fun seed(): String {
        check(pictures.model.states.value == ModelState.Ready)
        val codex = codexId()
        container.data.codexRepository.ensureCodex(codex, "Vision acceptance")
        val added = container.data.codexRepository.addItems(codex, library)
        return "added=$added"
    }

    /** On battery: the indexing job is scheduled, but after [IDLE_WAIT_MS] nothing has been tagged. */
    private suspend fun idle(): String {
        delay(IDLE_WAIT_MS)
        val progress = pictures.progress.first()
        check(progress.analysed == 0 && progress.total >= FIXTURES) { "on battery: $progress" }
        return "analysed=${progress.analysed} total=${progress.total}"
    }

    private suspend fun check(): String {
        val progress = pictures.progress.first { it.analysed >= FIXTURES }
        val index = pictures.tagger.index.first { index -> library.all { index[it.id] != null } }
        // The line drawings read as monochrome and greyscale; the colour pictures do not.
        val expected = listOf("monochrome", "greyscale").map(::normalizeMatchToken)
        val (drawings, colour) = library.partition { post -> index[post.id].orEmpty().tokens(PredictedTagUse.EVIDENCE).keys.containsAll(expected) }
        check(drawings.size == COLOUR_FROM && colour.all { it.id.sourcePostId.removePrefix("vision-").toInt() >= COLOUR_FROM }) {
            "monochrome on ${drawings.size} of $FIXTURES"
        }
        val tagged = drawings.size
        return "analysed=${progress.analysed}/${progress.total} tagged=$tagged ${forYou()} ${hidden()}"
    }

    /** The Library's predicted tags reach the For You profile, and lead a Danbooru lane with no provider tags at all. */
    private suspend fun forYou(): String {
        val inputs = container.features.tagAffinity.latest.first { it.pictures.kept >= FIXTURES }
        val token = normalizeMatchToken("monochrome")
        check(token in inputs.pictures.candidates) { "candidates ${inputs.pictures.candidates.size}" }
        val affinity = inputs.pictureAffinity(SourceKey.DANBOORU, token, token)
        check(affinity.score > 0.0) { "affinity $affinity" }
        val interests = ForYouInterests.generate(
            SourceKey.DANBOORU, emptyList(), inputs, pictureCandidates = inputs.pictures.candidates.associateWith { it },
        )
        check(interests.any { token in it.tags }) { "interests $interests" }
        return "candidates=${inputs.pictures.candidates.size} score=${"%.2f".format(java.util.Locale.ROOT, affinity.score)} lanes=${interests.size}"
    }

    /** An untagged post is caught by a hidden tag only through what the tagger sees in its picture. */
    private suspend fun hidden(): String {
        check(untagged.canonicalTags.isEmpty() && untagged.rawTags.isEmpty())
        val rules = mapOf(SourceKey.DANBOORU to setOf(normalizeMatchToken("no_humans")))
        check(!untagged.hiddenBy(rules, null)) { "hidden without a prediction" }
        val predicted = pictures.tagger.tagsFor(untagged)
        check(untagged.hiddenBy(rules, predicted)) { "not hidden; ${predicted.size} predictions" }
        return "hiddenPredictions=${predicted.size}"
    }

    private suspend fun delete(): String {
        pictures.model.delete()
        check(pictures.model.states.value == ModelState.Absent)
        check(!pictures.model.modelFile.exists() && !pictures.model.vocabularyFile.exists())
        check(pictures.tagger.index.first() == PredictedTagIndex.Empty)
        val progress = pictures.progress.first()
        check(progress.analysed == 0) { "after delete: $progress" }
        checkNotNull(container.data.codexRepository.deleteCodexForUndo(codexId()))
        return "analysed=${progress.analysed}"
    }

    private suspend fun codexId(): String =
        ProfileLibraryIds.codex(container.data.settingsRepository.observeSettings().first().activeProfileId, "vision-acceptance")
}

private fun fixturePost(id: String, picture: Int): Post {
    val image = ImageRef("${BuildConfig.VISION_MODEL_BASE_URL}fixtures/fixture-%02d.png".format(java.util.Locale.ROOT, picture), null, "image/png")
    return Post(
        id = PostId(SourceKey.DANBOORU, id),
        preview = image,
        full = image,
        media = listOf(image),
        pageUrl = "https://example.test/$id",
        width = 360,
        height = 360,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}

/**
 * Pictures 0 until [FIXTURES] fill the Library, [COLOUR_FROM] onwards in colour (scripts/vision_fixtures.py), so
 * monochrome is on three quarters of it: common, not universal. One more line drawing is the untagged candidate.
 */
private const val FIXTURES = 24
private const val COLOUR_FROM = 18
private const val TAG = "TheoriaVision"
private const val PHASE_TIMEOUT_MS = 300_000L
private const val IDLE_WAIT_MS = 10_000L
