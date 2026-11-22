package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post

/** Scores each Codex of a saved-library snapshot against the incoming selection, weighing tags by their rarity in it. */
fun CodexSaveRelevance.scoreEach(library: Map<String, List<Post>>): Map<String, Double> {
    val rarity = TagRarity.of(library.values.flatten())
    return library.mapValues { (_, saved) -> score(saved, rarity) }
}
