package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.vision.PredictedTagIndex

/**
 * A saved-library snapshot, every Codex's posts by Codex id, with the picture tagger's [predicted] tags and its
 * [TagRarity] counted once for every request that scores against it.
 */
class SavedLibrary(val postsByCodex: Map<String, List<Post>>, val predicted: PredictedTagIndex = PredictedTagIndex.Empty) {
    val rarity: TagRarity = TagRarity.of(postsByCodex.values.flatten(), predicted)
}

/** Scores each Codex of [library] against the incoming selection, weighing tags by their rarity in it. */
fun CodexSaveRelevance.scoreEach(library: SavedLibrary): Map<String, Double> =
    library.postsByCodex.mapValues { (_, saved) -> score(saved, library.rarity) }
