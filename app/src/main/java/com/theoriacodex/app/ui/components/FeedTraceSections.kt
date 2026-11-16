package com.theoriacodex.app.ui.components

/**
 * Trace sections around whole-feed passes, so a trace shows which thread ran each pass and for how long. Main-thread
 * time inside them is what `.docs/exec/shared-feed-presenter.html` measures. Pass one as `rememberComputedOffMain`'s
 * `section`, or open it with `traceMediaSection` where a pass runs outside that helper. They sit in composition-side
 * callers rather than inside the pure functions, which plain JVM tests call without an Android `Trace`.
 */
internal object FeedTraceSections {
    /** A visibility filter pass over a feed's posts (Search, For You, Creator, Codex detail). */
    const val FILTER = "TheoriaFeedFilter"

    /** A Codex sort in a Codex route boundary, saved or live. */
    const val SORT = "TheoriaFeedSort"

    /** Collecting the known animation lengths a length-sorted Codex needs. */
    const val KNOWN_DURATIONS = "TheoriaFeedKnownDurations"
}
