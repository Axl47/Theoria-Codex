package com.theoriacodex.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.vision.PredictedTagIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Keeps a pushed Triage route's [TriageDeck] across rotation: the deck collects its feed in [viewModelScope] and
 * closes when the route is gone, while its pending writes still finish in [writeScope].
 */
class TriageDeckViewModel(
    feed: TriageFeed,
    actions: TriageActions,
    quickSave: QuickSave,
    query: Query?,
    writeScope: CoroutineScope,
    removal: TriageRemoval? = null,
    predicted: Flow<PredictedTagIndex> = emptyFlow(),
) : ViewModel() {
    val deck = TriageDeck(
        viewModelScope, feed, actions, quickSave, query, fromForYou = false, writeScope, removal, predicted = predicted,
    )

    override fun onCleared() = deck.close()
}
