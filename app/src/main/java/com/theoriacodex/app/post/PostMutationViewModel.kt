package com.theoriacodex.app.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.ui.state.MutationFeedback
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Captured destinations and commit callbacks survive Activity recreation without a second membership state. */
internal class PostMutationViewModel(
    private val settings: SettingsRepository,
    private val toggle: suspend (RecommendationProfile, Post, List<String>) -> LikeToggleOutcome,
    scopeOverride: CoroutineScope? = null,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val commands = Mutex()
    val feedback = MutationFeedback()
    private val mutablePendingLikePostIds = MutableStateFlow<Map<String, Set<PostId>>>(emptyMap())
    val pendingLikePostIds = mutablePendingLikePostIds.asStateFlow()

    fun like(profile: RecommendationProfile, post: Post, onCommitted: (LikeToggleOutcome) -> Unit = {}) {
        scope.launch {
            commands.withLock {
                // Membership can emit before toggle returns. Retain the post through shelf setup.
                mutablePendingLikePostIds.value = mapOf(profile.profileId to setOf(post.id))
                try {
                    val result = runCatchingPreservingCancellation {
                        val tags = withContext(computationDispatcher) { trainingTagsFor(post) }
                        toggle(profile, post, tags)
                    }
                    result.fold(onSuccess = onCommitted, onFailure = {
                        feedback.add("Could not update Likes. Please try again.")
                    })
                } finally {
                    mutablePendingLikePostIds.value = emptyMap()
                }
            }
        }
    }

    /** Saves or removes one tag, or an "or" group when [tags] holds several; [name] labels a new group. */
    fun favorite(profileId: String, source: SourceKey, tags: List<String>, add: Boolean, name: String? = null) {
        updateFavorites {
            if (add) {
                val added = settings.addFavoriteTag(profileId, source, tags, name)
                feedback.add(if (added) "Added to favorite tags" else "Already in favorite tags")
            } else {
                settings.removeFavoriteTag(profileId, source, tags)
            }
        }
    }

    /** Names a saved group, or clears its name when [name] is blank. */
    fun renameFavorite(profileId: String, source: SourceKey, tags: List<String>, name: String) {
        updateFavorites { settings.renameFavoriteTag(profileId, source, tags, name) }
    }

    private fun updateFavorites(change: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                val result = runCatchingPreservingCancellation { change() }
                if (result.isFailure) feedback.add("Could not update favorite tags. Please try again.")
            }
        }
    }
}
