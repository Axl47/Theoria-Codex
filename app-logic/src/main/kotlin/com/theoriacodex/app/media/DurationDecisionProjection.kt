package com.theoriacodex.app.media

/** Inspect the bounded coordinator snapshot, retaining settled route values beyond its LRU. */
fun reconcileDurationDecisions(
    previous: Map<MediaDurationKey, MediaDurationState>,
    incoming: Map<MediaDurationKey, MediaDurationState>,
    observed: Set<MediaDurationKey>,
): Map<MediaDurationKey, MediaDurationState> {
    val changes = incoming.filter { (key, state) -> key in observed && previous[key] != state }
    if (changes.isEmpty()) return previous
    return buildMap(previous.size + changes.size) { putAll(previous); putAll(changes) }
}
