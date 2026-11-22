package com.theoriacodex.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.vision.PictureTags
import kotlinx.coroutines.CoroutineScope

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
    pictures: PictureTags = PictureTags.None,
) : ViewModel() {
    val deck = TriageDeck(
        viewModelScope, feed, actions, quickSave, query, fromForYou = false, writeScope, removal, pictures = pictures,
    )

    override fun onCleared() = deck.close()
}
