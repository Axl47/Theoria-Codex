package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.followKey

const val FOLLOWED_CODEX_ID = "system:followed"

fun selectFollowedCreators(
    follows: List<FollowedCreator>,
    sources: Set<String>,
    authors: Set<String>,
): List<FollowedCreator> = follows.filter { follow ->
    (sources.isEmpty() || follow.creator.source.id in sources) &&
        (authors.isEmpty() || follow.creator.followKey() in authors)
}
