# 0030. Usage statistics split by local calendar day, merge by maximum and seed tag counts once

Status: Accepted

## Context

Statistics are local and must survive DST changes, long gaps and restoring the same backup repeatedly.

## Decision

- `splitDailyUsage` allocates elapsed time with a wall-clock anchor in the local zone (midnight, DST) and only allocates dates inside the retention window, even after a long interval.
- `mergeRestored` keeps the larger value of each counter, so restoring a backup twice changes nothing.
- `searchesByTag` takes in the tags Recents already held once per kind of term (`searchesByTagSeeded`, `searchesByTypedTagSeeded`).
- Daily counters added later (`readingMs`, `animeMs`) read as zero for older days.
- Typed (non-general) searched terms live in `StatisticsTypedTagCountRecord`, apart from `StatisticsTagCountRecord`, so a build that predates typed counting skips them instead of reading an artist as a general tag.
- `seedSearchedTags` counts each manual search in Recents once per term and source. Each kind is seeded once on its own flag; a key already counted keeps the larger value, because searches made after counting began are also still in Recents.
- A completed search counts only the terms it required and ran. Unified runs only portable general tags, so terms left in a draft or owned by a source are not counted.
- Series time fields (`readingMs`, `animeMs`) are absent from stores and backups written before series time counted, and read as zero.
- A history key that is not an ISO date fails validation like any other bad field, so the store resets.
- A facet added by a later build is skipped rather than failing the whole store.
- `watchedByTag` is a Space-Saving table of at most `WATCHED_TAGS_PER_SOURCE` tags per source, and `watchedBySource` stays exact. Each source has a floor in `watchedTagFloors`, an upper bound on the count of any tag absent from its table. A view first counts the tags its source already holds, then adds the new ones. When a new tag reaches a full source, every entry tied at the lowest count leaves (their estimate equals the new floor, so nothing they said is lost), the floor rises to that count, and the new tag starts one above it. Every stored count is at least its source's floor; new tags enter in tag order, and a tag that comes back counts on from the floor. Counts therefore only overestimate, by at most the floor a tag inherited when it entered.
- `capWatchedTags` runs in `normalize`, on every load, write and restore. It drops entries below the floor, and a source over the limit keeps its highest counts, ties broken by tag, so exactly the limit remains; the largest count that does not fit becomes the floor. Version 2 files, written before the cap, load through it and are written back as version 3, which carries the floors; restoring merges counts and floors by maximum, then caps, so a twice-restored backup changes nothing.
- `WATCHED_TAGS_PER_SOURCE` was picked on a host fixture (`HeavyWatchedTagsTest`): 30,000 watched posts of 35 Zipf-distributed booru tags, 800 kept posts and eight planted interests. Uncapped, that history holds about 117,000 tags (about 5 MB rewritten per view). 4,000 is the smallest limit that keeps all eight interests proven in the same order as exact counts, with the same proven default lanes. Because tied entries leave together, a source holds between roughly 1,500 entries and the limit, and the fixture's floor settles near 138 of 30,000 watched posts. At 2,000 two interests swap and the weakest one's score halves.
- Pinning tags on kept posts against eviction was measured and rejected: a post's tags are counted when it is viewed, before it is kept, so a niche tag first seen on a full table already enters at the floor. Pinning kept about half of the niche tags, held about 11,700 entries (530 KB per view) at 800 kept posts, grew with the library, and would make statistics depend on library and Likes.
- `AppUsageTracker` is the process-lifetime owner of monotonic foreground timing and mutually exclusive route-category timing (`StatisticsUsageCategory`): at most one category counts at a time, and screens such as Settings count as foreground time with no category.

## Consequences

Counters are monotonic; there is no way to subtract on restore. The watched-tag limit is per source, so the file still grows with the number of sources watched, Custom Sites included; nothing caps the total. Tag counts on the Statistics screen can read a little high for tags that entered a full table. A floor is at most views times tags per post divided by the limit, so a heavy source's floor stays under a fixed share of its watched posts; a source with fewer tags per post has a lower floor.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/DailyStatistics.kt`: `splitDailyUsage`, `DailyActivity`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `StatisticsRepository.mergeRestored`, `seedSearchedTags`, `LifetimeStatistics`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsRepositories.kt`: `StatisticsTypedTagCountRecord`, `StatisticsStoreRecord`, `seedSearchedTags`, `parseHistoryDay`, `searchedTerms`, `STATISTICS_DATASTORE_SCHEMA_VERSION`, `OLDEST_STATISTICS_DATASTORE_SCHEMA_VERSION`, `recordWatched`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/WatchedTagCounts.kt`: `WATCHED_TAGS_PER_SOURCE`, `recordWatchedTags`, `capWatchedTags`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/HeavyWatchedTagsTest.kt`: `HeavyWatchedTagsTest`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/statistics/AppUsageTracker.kt`: `AppUsageTracker`, `StatisticsUsageCategory`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DailyStatisticsTest.kt`: `midnight slices retain foreground Other and respect a DST day (ADR 0030)`, `history is bounded by calendar age and count without changing lifetime totals (ADR 0030)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsRepositoriesTest.kt`: `restoring a backup keeps the larger count of each typed term and both seed flags (ADR 0030)`, `searched tags seed once from manual recent searches without double counting (ADR 0030)`, `typed terms seed once from Recents after general tags were seeded (ADR 0030)`, `typed counts keep their own list so older records and builds read general counts unchanged (ADR 0030)`, `searches count typed terms with their type and only what ran (ADR 0030)`, `a history key that is not a date resets the store and keeps the original aside (ADR 0030)`, `statistics additions saturate and negative deltas are ignored (ADR 0030)`, `days and stores written before series time read it as zero (ADR 0030)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `statistics travel with history and restoring them twice changes nothing (ADR 0030)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/WatchedTagCountsTest.kt`: `a new tag at a full source evicts the lowest count and starts one above it (ADR 0030)`, `tied lowest counts leave together and a tag seen again counts on from its estimate (ADR 0030)`, `a view counts the tags already held before adding new ones, whatever their order (ADR 0030)`, `an uncapped table keeps its top counts and floors at the largest count that did not fit (ADR 0030)`, `restoring a backup reads absent tags at each side's floor and changes nothing the second time (ADR 0030)`, `a version 2 file with an uncapped map loads capped and is written back as version 3 with its floor (ADR 0030)`
- `app/src/test/java/com/theoriacodex/app/statistics/AppUsageTrackerTest.kt`: `foreground timing uses mutually exclusive categories and ignores duplicate lifecycle events (ADR 0030)`, `returning from background records one new open and uncategorized time remains total only (ADR 0030)`, `route categories map home tabs and secondary destinations without overlap (ADR 0030)`
- Left to prose: `WATCHED_TAGS_PER_SOURCE` and the rejected pinning design are tuning choices measured on `HeavyWatchedTagsTest`'s fixture.
