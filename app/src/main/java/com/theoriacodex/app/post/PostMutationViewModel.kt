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
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    fun like(profile: RecommendationProfile, post: Post, onCommitted: (LikeToggleOutcome) -> Unit = {}) {
        scope.launch {
            commands.withLock {
                val result = runCatchingPreservingCancellation {
                    val tags = withContext(computationDispatcher) { trainingTagsFor(post) }
                    toggle(profile, post, tags)
                }
                result.fold(onSuccess = onCommitted, onFailure = {
                    feedback.add("Could not update Likes. Please try again.")
                })
            }
        }
    }

    fun favorite(profileId: String, source: SourceKey, tag: String, add: Boolean) {
        scope.launch {
            commands.withLock {
                val result = runCatchingPreservingCancellation {
                    if (add) settings.addFavoriteTag(profileId, source, tag)
                    else {
                        settings.removeFavoriteTag(profileId, source, tag)
                        true
                    }
                }
                when {
                    result.isFailure -> feedback.add("Could not update favorite tags. Please try again.")
                    add -> feedback.add(if (result.getOrThrow()) "Added to favorite tags" else "Already in favorite tags")
                }
            }
        }
    }
}
