package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.media.AnimatedDurationEnrichment
import com.theoriacodex.app.media.AnimatedDurationEnrichmentLane
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CodexDetailDurationState(
    val codexId: String? = null,
    val posts: List<Post> = emptyList(),
)

/** Navigation-scoped owner for immutable duration enrichment of one Codex collection. */
internal class CodexDetailDurationViewModel(
    animatedDurationEnricher: AnimatedDurationEnricher,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {
    private val ownerScope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(CodexDetailDurationState())
    val state: StateFlow<CodexDetailDurationState> = mutableState.asStateFlow()
    private val durationEnrichmentLane = AnimatedDurationEnrichmentLane(
        scope = ownerScope,
        enricher = animatedDurationEnricher,
        currentIdentity = { mutableState.value.codexId },
        currentPosts = { mutableState.value.posts },
        applyEnrichments = ::applyEnrichments,
    )

    fun synchronize(codexId: String, posts: List<Post>) {
        val previous = mutableState.value
        val previousById = if (previous.codexId == codexId) {
            previous.posts.associateBy(Post::id)
        } else {
            emptyMap()
        }
        val merged = posts.map { post ->
            val previousDuration = previousById[post.id]?.let(::animatedDurationMs)
            if (animatedDurationMs(post) == null && previousDuration != null) {
                post.copy(durationMs = previousDuration)
            } else {
                post
            }
        }
        mutableState.value = CodexDetailDurationState(codexId = codexId, posts = merged)
    }

    fun requestEnrichment(codexId: String) {
        durationEnrichmentLane.request(codexId)
    }

    private fun applyEnrichments(
        codexId: String,
        enrichments: List<AnimatedDurationEnrichment>,
    ) {
        val current = mutableState.value
        if (current.codexId != codexId) return
        val durationsByPostId = enrichments.associate { result -> result.postId to result.durationMs }
        mutableState.value = current.copy(
            posts = current.posts.map { post ->
                val duration = durationsByPostId[post.id]
                if (duration != null && animatedDurationMs(post) == null) {
                    post.copy(durationMs = duration)
                } else {
                    post
                }
            },
        )
    }

    companion object {
        fun factory(animatedDurationEnricher: AnimatedDurationEnricher): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CodexDetailDurationViewModel(animatedDurationEnricher) as T
                }
            }
        }
    }
}
