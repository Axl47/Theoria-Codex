package com.theoriacodex.app.creator

import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.data.repository.followKey

internal sealed interface FollowedCreatorRow {
    val follow: FollowedCreator
    val key: String

    data class Followed(override val follow: FollowedCreator) : FollowedCreatorRow {
        override val key: String get() = follow.membershipId
    }

    data class Vacated(override val follow: FollowedCreator) : FollowedCreatorRow {
        override val key: String get() = "vacated:${follow.membershipId}"
    }
}

internal fun followedCreatorRows(follows: List<FollowedCreator>, removed: RemovedFollow?): List<FollowedCreatorRow> {
    val rows = follows.map<FollowedCreator, FollowedCreatorRow>(FollowedCreatorRow::Followed).toMutableList()
    val gone = removed?.takeIf { r -> follows.none { it.creator.followKey() == r.follow.creator.followKey() } }
    if (gone != null) rows.add(gone.index.coerceIn(0, rows.size), FollowedCreatorRow.Vacated(gone.follow))
    return rows.sortedByDescending { it.follow.newPostCount }
}
