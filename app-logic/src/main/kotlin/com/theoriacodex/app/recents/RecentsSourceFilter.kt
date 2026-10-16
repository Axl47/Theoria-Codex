package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/** Multi-source history matches any recorded participant without changing its replay payload. */
fun RecentSearchEntry.matchesSource(source: SourceKey?): Boolean =
    source == null || source in sources || (query.mode as? QueryMode.Source)?.source == source

fun RecentActivityEntry.matchesSource(source: SourceKey?): Boolean = when (this) {
    is RecentActivityEntry.Watched -> source == null || entry.post.id.source == source
    is RecentActivityEntry.Search -> entry.matchesSource(source)
}
