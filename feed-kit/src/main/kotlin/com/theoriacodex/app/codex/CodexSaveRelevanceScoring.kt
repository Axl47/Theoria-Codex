package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post

/** Scores each Codex of a saved-library snapshot against the incoming selection. */
fun CodexSaveRelevance.scoreEach(library: Map<String, List<Post>>): Map<String, Double> =
    library.mapValues { (_, saved) -> score(saved) }
