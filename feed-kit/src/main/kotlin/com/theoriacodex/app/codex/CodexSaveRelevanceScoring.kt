package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.vision.PredictedTagIndex

class SavedLibrary(val postsByCodex: Map<String, List<Post>>, val predicted: PredictedTagIndex = PredictedTagIndex.Empty) {
    val rarity: TagRarity = TagRarity.of(postsByCodex.values.flatten(), predicted)
}

fun CodexSaveRelevance.scoreEach(library: SavedLibrary): Map<String, Double> =
    library.postsByCodex.mapValues { (_, saved) -> score(saved, library.rarity) }
