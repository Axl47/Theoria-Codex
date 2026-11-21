package com.theoriacodex.app.creator

import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.data.repository.followKey

/** One row of the followed-creators sheet: a follow, or the place an unfollowed one held while its Undo is live. */
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

/**
 * The sheet's rows: creators with new posts first, otherwise in follow order, with [removed] holding its place
 * until it is restored or its creator is followed again.
 */
internal fun followedCreatorRows(follows: List<FollowedCreator>, removed: RemovedFollow?): List<FollowedCreatorRow> {
    val rows = follows.map<FollowedCreator, FollowedCreatorRow>(FollowedCreatorRow::Followed).toMutableList()
    val gone = removed?.takeIf { r -> follows.none { it.creator.followKey() == r.follow.creator.followKey() } }
    if (gone != null) rows.add(gone.index.coerceIn(0, rows.size), FollowedCreatorRow.Vacated(gone.follow))
    return rows.sortedByDescending { it.follow.newPostCount }
}
