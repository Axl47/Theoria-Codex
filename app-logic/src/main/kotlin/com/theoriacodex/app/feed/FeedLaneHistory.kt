package com.theoriacodex.app.feed

/** One laid-out card: its stable key, the column the grid placed it in, and its height. */
data class FeedLanePlacement(val key: Any, val lane: Int, val heightPx: Int = 0)

/** Cards that changed column or height since they were last seen. A height change is what makes columns re-balance. */
data class FeedLaneChanges(val moved: Int, val resized: Int) {
    val any: Boolean get() = moved > 0 || resized > 0
}

/** Remembers each card's column and height, and reports cards that changed either since last seen. */
class FeedLaneHistory {
    private val seen = HashMap<Any, FeedLanePlacement>()

    fun record(placements: List<FeedLanePlacement>): FeedLaneChanges {
        var moved = 0
        var resized = 0
        placements.forEach { placement ->
            val previous = seen.put(placement.key, placement) ?: return@forEach
            if (previous.lane != placement.lane) moved += 1
            if (previous.heightPx != placement.heightPx) resized += 1
        }
        return FeedLaneChanges(moved, resized)
    }
}
