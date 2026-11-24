package com.theoriacodex.domain.vision

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PredictedTagIndex(
    val byPost: Map<PostId, List<PredictedTag>> = emptyMap(),
    val baseRates: Map<String, Double> = emptyMap(),
) {
    operator fun get(id: PostId): List<PredictedTag>? = byPost[id]

    companion object {
        val Empty = PredictedTagIndex()
    }
}

sealed interface PictureLook {
    data class Seen(val tags: List<PredictedTag>) : PictureLook

    data object Unseen : PictureLook

    data object Blind : PictureLook
}

interface PictureTags {
    val ready: StateFlow<Boolean>

    val library: StateFlow<PredictedTagIndex>

    fun known(id: PostId): List<PredictedTag>?

    suspend fun prepare() = Unit

    suspend fun look(post: Post): PictureLook

    object None : PictureTags {
        override val ready: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val library: StateFlow<PredictedTagIndex> = MutableStateFlow(PredictedTagIndex.Empty).asStateFlow()
        override fun known(id: PostId): List<PredictedTag>? = null
        override suspend fun look(post: Post): PictureLook = PictureLook.Blind
    }
}
