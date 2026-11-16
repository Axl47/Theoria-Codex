package com.theoriacodex.app.ui.components

/**
 * Trace sections around whole-feed passes, so a trace shows which thread ran each pass and for how long. Main-thread
 * time inside them is what `.docs/exec/shared-feed-presenter.html` measures. Open them with `traceMediaSection`.
 */
internal object FeedTraceSections {
    /** A visibility filter pass over a feed's posts (Search, For You, Creator, Codex detail). */
    const val FILTER = "TheoriaFeedFilter"

    /** A Codex sort, saved or live. */
    const val SORT = "TheoriaFeedSort"

    /** Collecting the known animation lengths of a list of posts. */
    const val KNOWN_DURATIONS = "TheoriaFeedKnownDurations"
}
