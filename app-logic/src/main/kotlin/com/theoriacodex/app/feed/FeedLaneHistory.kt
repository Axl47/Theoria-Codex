package com.theoriacodex.app.feed

/** One laid-out card: its stable key and the column the grid placed it in. */
data class FeedLanePlacement(val key: Any, val lane: Int)

/** Remembers each card's column, and reports cards that moved to another column since last seen. */
class FeedLaneHistory {
    private val lanes = HashMap<Any, Int>()

    /** Returns how many cards in [placements] changed column. */
    fun record(placements: List<FeedLanePlacement>): Int {
        return placements.count { placement ->
            val previous = lanes.put(placement.key, placement.lane)
            previous != null && previous != placement.lane
        }
    }
}
