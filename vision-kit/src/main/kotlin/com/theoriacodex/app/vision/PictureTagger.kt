package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PictureLook
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
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

    /** The preview could not be loaded; it may load later. */
    data object Unreachable : PictureResult

    /** The model is not ready, or would not load; nothing can be read until it does. */
    data object Unavailable : PictureResult
}

/**
 * The picture tagger, whatever the source: [analyze] reads a post's still preview with the downloaded model, and
 * [library] holds what Room stores for library posts, while [known] also answers from this session's analyses of For You
 * candidates without anything observing them. While the model is absent [library] is empty and [analyze] answers
 * [PictureResult.Unavailable], so every consumer behaves as before the tagger existed.
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
    private val session = SessionAnalyses()

    // Held while results are kept and while a deleted model's are forgotten, so none outlive the delete.
    private val commits = Mutex()
    private val current: Boolean get() = store.states.value == ModelState.Ready

    val modelId: String get() = store.id

    override val ready: StateFlow<Boolean> =
        store.states.map { it == ModelState.Ready }.stateIn(scope, SharingStarted.Eagerly, store.states.value == ModelState.Ready)

    /** Library rows settle for [LIBRARY_SETTLE_MS] after the first, so a batch of stored analyses emits once. */
    override val library: StateFlow<PredictedTagIndex> = ready.flatMapLatest { ready ->
        if (!ready) flowOf(PredictedTagIndex.Empty) else combine(stored(), flow { emit(baseRates()) }, ::PredictedTagIndex)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PredictedTagIndex.Empty)

    override fun known(id: PostId): List<PredictedTag>? {
        if (!ready.value) return null
        return when (val result = session[id]) {
            is PictureResult.Analysed -> result.tags
            PictureResult.Unanalysable -> emptyList()
            else -> library.value[id]
        }
    }

    override suspend fun prepare() {
        if (!ready.value) return
        runCatchingPreservingCancellation {
            vocabulary()
            holder.use { }
        }
    }

    private fun stored(): Flow<Map<PostId, List<PredictedTag>>> {
        var first = true
        return signatures.observePredictedTags(store.id).debounce { if (first.also { first = false }) 0L else LIBRARY_SETTLE_MS }
    }

    /**
     * [post]'s picture through the model, once per session when [remember]: later calls answer from memory. A failed
     * load is not remembered, so the next call tries again. The Library's indexing does not remember, since it stores.
     */
    suspend fun analyze(post: Post, remember: Boolean = true): PictureResult {
        session[post.id]?.let { return it }
        if (!ready.value) return PictureResult.Unavailable
        val result = if (!hasStill(post)) PictureResult.Unanalysable else read(post)
        if (!remember || result == PictureResult.Unavailable || result == PictureResult.Unreachable) return result
        return commits.withLock {
            // A model deleted while this ran must not leave its answer behind.
            if (current) result.also { session[post.id] = result } else PictureResult.Unavailable
        }
    }

    override suspend fun look(post: Post): PictureLook {
        library.value[post.id]?.let { return PictureLook.Seen(it) }
        return when (val result = analyze(post)) {
            is PictureResult.Analysed -> PictureLook.Seen(result.tags)
            PictureResult.Unanalysable -> PictureLook.Seen(emptyList())
            PictureResult.Unreachable -> PictureLook.Unseen
            PictureResult.Unavailable -> PictureLook.Blind
        }
    }

    /** What this session already learned about [postId]'s picture, without analysing it. */
    fun remembered(postId: PostId): PictureResult? = session[postId]

    private suspend fun baseRates(): Map<String, Double> =
        if (!ready.value) emptyMap() else runCatchingPreservingCancellation { vocabulary().baseRates().mapKeys { normalizeMatchToken(it.key) } }.getOrDefault(emptyMap())

    /** Frees the loaded model now, as under memory pressure; the next analysis loads it again. */
    suspend fun release() = holder.release()

    /**
     * Forgets everything after the model was deleted: the loaded model, the vocabulary, this session's analyses and what
     * the Library stored.
     */
    suspend fun forget() = commits.withLock {
        holder.release()
        vocabularyLock.withLock { vocabulary = null }
        session.clear()
        signatures.clearVision()
    }

    /**
     * Stores [results] for the Library's posts, leaving out pictures that could not be read now; returns false, storing
     * nothing, once the model was deleted.
     */
    suspend fun store(results: Map<PostId, PictureResult>): Boolean = commits.withLock {
        if (!current) return@withLock false
        val visions = results.mapNotNull { (id, result) ->
            when (result) {
                is PictureResult.Analysed -> PictureVision(id, store.id, result.embedding, result.tags)
                PictureResult.Unanalysable -> PictureVision(id, store.id, embedding = null, tags = null)
                PictureResult.Unreachable, PictureResult.Unavailable -> null
            }
        }
        if (visions.isNotEmpty()) signatures.putVision(visions)
        true
    }

    /**
     * Only a loaded model failing on this picture makes it [PictureResult.Unanalysable]; a model or vocabulary that
     * fails to load (low memory, say) leaves it [PictureResult.Unavailable] for a later try.
     */
    private suspend fun read(post: Post): PictureResult {
        val picture = runCatchingPreservingCancellation { loadStill(post) }.getOrNull() ?: return PictureResult.Unreachable
        return runCatchingPreservingCancellation {
            val words = vocabulary()
            val analysis = holder.use { model -> model.analyze(picture) }
            PictureResult.Analysed(words.predictions(analysis.scores), PictureEmbedding.encode(analysis.embedding))
        }.getOrElse { error ->
            Diagnostics.report("vision", "The picture tagger could not read a picture", error)
            if (error is PictureModelHolder.LoadFailed || vocabulary == null || !ready.value) PictureResult.Unavailable else PictureResult.Unanalysable
        }
    }

    private suspend fun vocabulary(): TagVocabulary = vocabularyLock.withLock {
        vocabulary ?: withContext(Dispatchers.IO) { store.vocabularyFile.reader().use(TagVocabulary::read) }.also { vocabulary = it }
    }

    private companion object {
        const val LIBRARY_SETTLE_MS = 2_000L
    }
}

/** This session's analyses, the most recent [LIMIT] kept; read and written from any thread. */
private class SessionAnalyses {
    private val byPost = object : LinkedHashMap<PostId, PictureResult>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PostId, PictureResult>) = size > LIMIT
    }

    operator fun get(id: PostId): PictureResult? = synchronized(byPost) { byPost[id] }

    operator fun set(id: PostId, result: PictureResult) = synchronized(byPost) {
        byPost.remove(id)
        byPost[id] = result
    }

    fun clear() = synchronized(byPost) { byPost.clear() }

    private companion object {
        const val LIMIT = 2_048
    }
}
