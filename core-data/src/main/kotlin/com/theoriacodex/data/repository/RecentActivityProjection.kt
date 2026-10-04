package com.theoriacodex.data.repository

/** Derive All from the same snapshots as the section tabs; never load their payloads a second time. */
fun mergeRecentActivity(
    watched: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>,
): List<RecentActivityEntry> = buildList {
    watched.filterNot { it.section == RecentPostSection.FYP }.sortedByDescending { it.viewedAtEpochMs }.distinctBy { it.post.id }
        .forEach { add(RecentActivityEntry.Watched(it)) }
    searches.forEach { add(RecentActivityEntry.Search(it)) }
}.sortedWith(compareByDescending<RecentActivityEntry> { it.occurredAtEpochMs }
    .thenBy { if (it is RecentActivityEntry.Watched) 0 else 1 })
