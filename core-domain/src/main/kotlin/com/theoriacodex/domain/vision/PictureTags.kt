package com.theoriacodex.domain.vision

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the picture tagger knows now: predicted tags by post, and each predicted tag's share of Danbooru posts
 * ([baseRates], keyed by Danbooru tag as `normalizeMatchToken` spells it), which stands in for what a profile watches until it has watched enough.
 */
data class PredictedTagIndex(
    val byPost: Map<PostId, List<PredictedTag>> = emptyMap(),
    val baseRates: Map<String, Double> = emptyMap(),
) {
    operator fun get(id: PostId): List<PredictedTag>? = byPost[id]

    companion object {
        val Empty = PredictedTagIndex()
    }
}

/**
 * The opt-in picture tagger as features see it. While its model is absent, [ready] is false, [index] is empty and
 * [tagsFor] answers nothing, so every feature behaves as it did before the tagger existed.
 */
interface PictureTags {
    /** Whether the model is downloaded and verified. */
    val ready: StateFlow<Boolean>

    /** Every predicted tag known now: library posts' stored ones and this session's analyses. */
    val index: StateFlow<PredictedTagIndex>

    /** The Library's stored predictions only, which change when the Library is indexed, not with every card vetted. */
    val library: StateFlow<PredictedTagIndex>

    /**
     * [post]'s predicted tags, analysing its picture when this session has not yet (about half a second). Empty when
     * the model is absent or the picture cannot be read, so a caller waiting on it never waits twice for one post.
     */
    suspend fun tagsFor(post: Post): List<PredictedTag>

    /** No tagger: for builds and tests without one. */
    object None : PictureTags {
        override val ready: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val index: StateFlow<PredictedTagIndex> = MutableStateFlow(PredictedTagIndex.Empty).asStateFlow()
        override val library: StateFlow<PredictedTagIndex> get() = index
        override suspend fun tagsFor(post: Post): List<PredictedTag> = emptyList()
    }
}
