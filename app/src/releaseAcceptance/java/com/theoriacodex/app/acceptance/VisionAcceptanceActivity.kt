package com.theoriacodex.app.acceptance

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.PerceptualHash
import com.theoriacodex.app.recommend.ForYouInterests
import com.theoriacodex.app.recommend.pictureAffinity
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.triage.hiddenBy
import com.theoriacodex.app.viewer.FrameSample
import com.theoriacodex.app.viewer.NetworkBudget
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.app.vision.AppPictures
import com.theoriacodex.app.vision.ModelState
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.isSamePicture
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PictureLook
import com.theoriacodex.domain.vision.PredictedTagIndex
import com.theoriacodex.domain.vision.PredictedTagUse
import com.theoriacodex.domain.vision.tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
                withTimeout(PHASE_TIMEOUT_MS) { VisionPhases(applicationContext, container, checkNotNull(container.pictures)).run(phase) }
            }
            outcome.onSuccess { Log.i(TAG, "PASS:$phase:$it") }
                .onFailure { Log.e(TAG, "FAIL:$phase:${it.javaClass.simpleName}:${it.message}") }
            status.text = "Vision acceptance: $phase ${if (outcome.isSuccess) "passed" else "failed"}"
        }
    }
}

private class VisionPhases(
    private val context: Context,
    private val container: TheoriaAppContainer,
    private val pictures: AppPictures,
) {
    private val library = List(FIXTURES) { fixturePost("vision-$it", it) }

    // Not in the Library and without provider tags, like a For You candidate a source left untagged.
    private val untagged = fixturePost("vision-untagged", FIXTURES)

    // The same picture, tagged as richly as a booru post (none of the tags is hidden).
    private val wellTagged = fixturePost("vision-well-tagged", FIXTURES).copy(canonicalTags = WELL_TAGGED_TAGS)

    // Videos of line drawings behind a colour poster: only their frames read as monochrome.
    private val videos = VIDEOS.map(::videoPost)

    suspend fun run(phase: String): String = when (phase) {
        "download" -> download()
        "seed" -> seed()
        "idle" -> idle()
        "stills" -> stills()
        "check" -> check()
        "sample" -> sample()
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
        val added = container.data.codexRepository.addItems(codex, library + videos)
        return "added=$added"
    }

    /** On battery: the indexing job is scheduled, but after [IDLE_WAIT_MS] nothing has been tagged. */
    private suspend fun idle(): String {
        delay(IDLE_WAIT_MS)
        val progress = pictures.progress.first()
        check(progress.analysed == 0 && progress.total >= FIXTURES) { "on battery: $progress" }
        return "analysed=${progress.analysed} total=${progress.total}"
    }

    /**
     * Charging with the app on screen: every post is indexed from its still, the videos from their colour posters, so no
     * video carries the monochrome that only its frames show. Progress leaves the videos out until their frames are read.
     */
    private suspend fun stills(): String {
        pictures.progress.first { it.analysed >= FIXTURES }
        val index = pictures.tagger.library.first { index -> videos.all { index[it.id] != null } }
        val progress = pictures.progress.first()
        check(progress.analysed == FIXTURES) { "videos counted before their frames: $progress" }
        val monochrome = normalizeMatchToken("monochrome")
        check(videos.none { monochrome in index[it.id].orEmpty().tokens(PredictedTagUse.EVIDENCE) }) { "a video was read from frames" }
        return "analysed=${progress.analysed}/${progress.total} videosFromStills=${videos.size}"
    }

    private suspend fun check(): String {
        val progress = pictures.progress.first { it.analysed >= FIXTURES + videos.size }
        val monochrome = normalizeMatchToken("monochrome")
        // The idle job replaced each video's still analysis with one that read its line-drawing frames.
        val stored = withTimeoutOrNull(IDLE_WAIT_MS) {
            pictures.tagger.library.first { index -> videos.all { monochrome in index[it.id].orEmpty().tokens(PredictedTagUse.EVIDENCE) } }
        } ?: pictures.tagger.library.value
        val framed = videos.count { monochrome in stored[it.id].orEmpty().tokens(PredictedTagUse.EVIDENCE) }
        check(framed == videos.size) { "monochrome on $framed of ${videos.size} videos" }
        val index = pictures.tagger.library.first { index -> library.all { index[it.id] != null } }
        // The line drawings read as monochrome and greyscale; the colour pictures do not.
        val expected = listOf("monochrome", "greyscale").map(::normalizeMatchToken)
        val (drawings, colour) = library.partition { post -> index[post.id].orEmpty().tokens(PredictedTagUse.EVIDENCE).keys.containsAll(expected) }
        check(drawings.size == COLOUR_FROM && colour.all { it.id.sourcePostId.removePrefix("vision-").toInt() >= COLOUR_FROM }) {
            "monochrome on ${drawings.size} of $FIXTURES"
        }
        val tagged = drawings.size
        return "analysed=${progress.analysed}/${progress.total} tagged=$tagged videosFromFrames=$framed ${forYou()} ${hidden()}"
    }

    /**
     * Each fixture video sampled as the job does it, timed, with the frames from different points told apart (each
     * second shows another drawing); the host counts the bytes each fetched.
     */
    private suspend fun sample(): String = buildList {
        for (file in VIDEOS) {
            val started = SystemClock.elapsedRealtime()
            val sample = context.videoPlaybackInfrastructure()
                .sampleFrames(videoUrl(file), SourceKey.DANBOORU.requestHeaders(), 6, 448, NetworkBudget(6L shl 20))
            val ms = SystemClock.elapsedRealtime() - started
            check(sample is FrameSample.Frames) { "$file sampled $sample" }
            val distinct = sample.frames.mapNotNull { frame ->
                IntArray(frame.width * frame.height).also { frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height) }
                    .let { PerceptualHash.ofArgb(it, frame.width, frame.height) }
            }.fold(emptyList<Long>()) { kept, hash -> if (kept.any { isSamePicture(hash, it) }) kept else kept + hash }.size
            check(distinct >= MIN_DISTINCT_FRAMES) { "$file gave $distinct distinct frames" }
            add("$file=${sample.frames.size}frames/${distinct}distinct/${ms}ms")
        }
    }.joinToString(" ")

    /** The Library's predicted tags reach the For You profile, and lead a Danbooru lane with no provider tags at all. */
    private suspend fun forYou(): String {
        val inputs = container.features.tagAffinity.latest.first { it.pictures.kept >= FIXTURES }
        val token = normalizeMatchToken("monochrome")
        check(token in inputs.pictures.candidates) { "candidates ${inputs.pictures.candidates.size}" }
        val affinity = inputs.pictureAffinity(SourceKey.DANBOORU, token, token)
        check(affinity.score > 0.0) { "affinity $affinity" }
        val interests = ForYouInterests.prepare(
            SourceKey.DANBOORU, emptyList(), inputs, pictureCandidates = inputs.pictures.candidates.associateWith { it },
        ).pick()
        check(interests.any { token in it.tags }) { "interests $interests" }
        return "candidates=${inputs.pictures.candidates.size} score=${"%.2f".format(java.util.Locale.ROOT, affinity.score)} lanes=${interests.size}"
    }

    /**
     * An untagged post is caught by a hidden tag only through what the tagger sees in its picture; the same picture on a
     * post its provider tagged richly is left to its own tags.
     */
    private suspend fun hidden(): String {
        check(untagged.canonicalTags.isEmpty() && untagged.rawTags.isEmpty())
        val rules = mapOf(SourceKey.DANBOORU to setOf(normalizeMatchToken("no_humans")))
        check(!untagged.hiddenBy(rules)) { "hidden without the tagger" }
        check(pictures.tagger.look(untagged) is PictureLook.Seen) { "picture not seen" }
        val predicted = pictures.tagger.known(untagged.id).orEmpty()
        check(untagged.hiddenBy(rules, pictures.tagger)) { "not hidden; ${predicted.size} predictions" }
        check(pictures.tagger.look(wellTagged) is PictureLook.Seen) { "well-tagged picture not seen" }
        check(!wellTagged.hiddenBy(rules, pictures.tagger)) { "a well-tagged post was hidden by its picture" }
        return "hiddenPredictions=${predicted.size} wellTaggedKept=true"
    }

    private suspend fun delete(): String {
        pictures.model.delete()
        check(pictures.model.states.value == ModelState.Absent)
        check(!pictures.model.modelFile.exists() && !pictures.model.vocabularyFile.exists())
        // The index empties as the tagger sees the model go, a moment after the delete returns.
        checkNotNull(withTimeoutOrNull(IDLE_WAIT_MS) { pictures.tagger.library.first { it == PredictedTagIndex.Empty } }) { "index kept" }
        val progress = pictures.progress.first()
        check(progress.analysed == 0) { "after delete: $progress" }
        checkNotNull(container.data.codexRepository.deleteCodexForUndo(codexId()))
        return "analysed=${progress.analysed}"
    }

    private suspend fun codexId(): String =
        ProfileLibraryIds.codex(container.data.settingsRepository.observeSettings().first().activeProfileId, "vision-acceptance")
}

private fun videoUrl(file: String) = "${BuildConfig.VISION_MODEL_BASE_URL}fixtures/$file"

/** A Danbooru-like video post: the colour fixture [COLOUR_FROM] as its poster, the video as its full media. */
private fun videoPost(file: String): Post {
    val video = ImageRef(videoUrl(file), null, if (file.endsWith(".webm")) "video/webm" else "video/mp4")
    return fixturePost("vision-$file", COLOUR_FROM).copy(full = video, media = listOf(video))
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
private val WELL_TAGGED_TAGS = listOf(
    "1girl", "solo", "long_hair", "smile", "looking_at_viewer", "monochrome", "greyscale", "lineart", "white_background",
    "simple_background", "sketch",
)
private const val COLOUR_FROM = 18
private val VIDEOS = listOf("video-start.mp4", "video-tail.mp4", "video.webm")
private const val TAG = "TheoriaVision"
private const val PHASE_TIMEOUT_MS = 300_000L
private const val IDLE_WAIT_MS = 10_000L

/** Of the six points across a six-drawing video, at least this many must show different drawings. */
private const val MIN_DISTINCT_FRAMES = 4
