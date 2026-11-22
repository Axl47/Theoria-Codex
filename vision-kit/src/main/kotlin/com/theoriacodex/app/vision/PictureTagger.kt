package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What analysing one post's picture gave. */
sealed interface PictureResult {
    /** The picture's predicted tags (at or above [PredictedTag.STORED_FLOOR]) and its encoded embedding. */
    class Analysed(val tags: List<PredictedTag>, val embedding: ByteArray) : PictureResult

    /** The post has no still preview, or the model could not read it; asking again gives the same answer. */
    data object Unanalysable : PictureResult

    /** The preview could not be loaded, or the model is not ready; it may work later. */
    data object Unavailable : PictureResult
}

/**
 * The picture tagger, whatever the source: [analyze] reads a post's still preview with the downloaded model, and
 * [index] holds every predicted tag known now, from Room for library posts and from this session's analyses for For You
 * candidates. While the model is absent [index] is empty and [analyze] answers [PictureResult.Unavailable], so every
 * consumer behaves as before the tagger existed.
 *
 * [loadStill] decodes a post's still preview (null when it has none or it failed to load: [hasStill] tells them apart).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PictureTagger(
    private val store: VisualModelStore,
    private val signatures: VisualSignatureRepository,
    private val hasStill: (Post) -> Boolean,
    private val loadStill: suspend (Post) -> Bitmap?,
    scope: CoroutineScope,
    threads: Int = WdTaggerRunner.DEFAULT_THREADS,
) : PictureTags {
    private val vocabularyLock = Mutex()
    private var vocabulary: TagVocabulary? = null
    private val holder = PictureModelHolder(scope) { WdTaggerRunner(store.modelFile, checkNotNull(vocabulary).size, threads) }
    private val session = MutableStateFlow(SessionAnalyses())

    val modelId: String get() = store.id

    override val ready: StateFlow<Boolean> =
        store.states.map { it == ModelState.Ready }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Library rows settle for [LIBRARY_SETTLE_MS], so a batch of stored analyses emits once. */
    override val index: StateFlow<PredictedTagIndex> = ready.flatMapLatest { ready ->
        if (!ready) {
            flowOf(PredictedTagIndex.Empty)
        } else {
            combine(library(), session, flow { emit(baseRates()) }) { stored, analysed, rates ->
                PredictedTagIndex(stored + analysed.tags(), rates)
            }
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PredictedTagIndex.Empty)

    private fun library(): Flow<Map<PostId, List<PredictedTag>>> =
        signatures.observePredictedTags(store.id).debounce(LIBRARY_SETTLE_MS)

    /**
     * [post]'s picture through the model, once per session when [remember]: later calls answer from memory. A failed
     * load is not remembered, so the next call tries again. The Library's indexing does not remember, since it stores.
     */
    suspend fun analyze(post: Post, remember: Boolean = true): PictureResult {
        session.value[post.id]?.let { return it }
        if (!ready.value) return PictureResult.Unavailable
        val result = if (!hasStill(post)) PictureResult.Unanalysable else read(post)
        if (remember && result != PictureResult.Unavailable) session.value = session.value.with(post.id, result)
        return result
    }

    override suspend fun tagsFor(post: Post): List<PredictedTag> = (analyze(post) as? PictureResult.Analysed)?.tags.orEmpty()

    /** What this session already learned about [postId]'s picture, without analysing it. */
    fun remembered(postId: PostId): PictureResult? = session.value[postId]

    private suspend fun baseRates(): Map<String, Double> =
        if (!ready.value) emptyMap() else runCatchingPreservingCancellation { vocabulary().baseRates() }.getOrDefault(emptyMap())

    /** Frees the loaded model now, as under memory pressure; the next analysis loads it again. */
    suspend fun release() = holder.release()

    /** Forgets everything after the model was deleted: the loaded model, the vocabulary and this session's analyses. */
    suspend fun forget() {
        holder.release()
        vocabularyLock.withLock { vocabulary = null }
        session.value = SessionAnalyses()
    }

    /** [results] as stored analyses for [modelId]; [PictureResult.Unavailable] results are left out. */
    fun visions(results: Map<PostId, PictureResult>): List<PictureVision> = results.mapNotNull { (id, result) ->
        when (result) {
            is PictureResult.Analysed -> PictureVision(id, store.id, result.embedding, result.tags)
            PictureResult.Unanalysable -> PictureVision(id, store.id, embedding = null, tags = null)
            PictureResult.Unavailable -> null
        }
    }

    private suspend fun read(post: Post): PictureResult {
        val picture = runCatchingPreservingCancellation { loadStill(post) }.getOrNull() ?: return PictureResult.Unavailable
        return runCatchingPreservingCancellation {
            val words = vocabulary()
            val analysis = holder.use { model -> model.analyze(picture) }
            PictureResult.Analysed(words.predictions(analysis.scores), PictureEmbedding.encode(analysis.embedding))
        }.getOrElse { error ->
            Diagnostics.report("vision", "The picture tagger could not read a picture", error)
            if (store.states.value == ModelState.Ready) PictureResult.Unanalysable else PictureResult.Unavailable
        }
    }

    private suspend fun vocabulary(): TagVocabulary = vocabularyLock.withLock {
        vocabulary ?: withContext(Dispatchers.IO) { store.vocabularyFile.reader().use(TagVocabulary::read) }.also { vocabulary = it }
    }

    private companion object {
        const val LIBRARY_SETTLE_MS = 2_000L
    }
}

/** This session's analyses, the most recent [LIMIT] kept. Immutable, so [PictureTagger.index] can compare snapshots. */
private class SessionAnalyses(private val byPost: LinkedHashMap<PostId, PictureResult> = LinkedHashMap()) {
    operator fun get(id: PostId): PictureResult? = byPost[id]

    fun with(id: PostId, result: PictureResult): SessionAnalyses {
        val next = LinkedHashMap(byPost)
        next.remove(id)
        next[id] = result
        while (next.size > LIMIT) next.remove(next.keys.first())
        return SessionAnalyses(next)
    }

    fun tags(): Map<PostId, List<PredictedTag>> =
        byPost.mapNotNull { (id, result) -> (result as? PictureResult.Analysed)?.let { id to it.tags } }.toMap()

    private companion object {
        const val LIMIT = 2_048
    }
}
