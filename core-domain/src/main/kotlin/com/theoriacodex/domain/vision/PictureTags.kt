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

/** What the picture tagger could tell about one post's picture. */
sealed interface PictureLook {
    /** The picture's predicted tags; empty when there is no still picture to read. */
    data class Seen(val tags: List<PredictedTag>) : PictureLook

    /** The preview did not load, so nothing is known about the picture now. */
    data object Unseen : PictureLook

    /** The model is absent or would not load: no picture can be read now. */
    data object Blind : PictureLook
}

/**
 * The opt-in picture tagger as features see it. While its model is absent, [ready] is false, [index] is empty and
 * [look] is [PictureLook.Blind], so every feature behaves as it did before the tagger existed.
 */
interface PictureTags {
    /** Whether the model is downloaded and verified. */
    val ready: StateFlow<Boolean>

    /** Every predicted tag known now: library posts' stored ones and this session's analyses. */
    val index: StateFlow<PredictedTagIndex>

    /** The Library's stored predictions only, which change when the Library is indexed, not with every card vetted. */
    val library: StateFlow<PredictedTagIndex>

    /**
     * What [post]'s picture shows, analysing it when this session has not yet (about half a second); an answer other
     * than [PictureLook.Seen] is not remembered, so asking again tries again.
     */
    suspend fun look(post: Post): PictureLook

    /** No tagger: for builds and tests without one. */
    object None : PictureTags {
        override val ready: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val index: StateFlow<PredictedTagIndex> = MutableStateFlow(PredictedTagIndex.Empty).asStateFlow()
        override val library: StateFlow<PredictedTagIndex> get() = index
        override suspend fun look(post: Post): PictureLook = PictureLook.Blind
    }
}
