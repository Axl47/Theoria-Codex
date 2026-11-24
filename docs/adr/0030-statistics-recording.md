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

## Consequences

Counters are monotonic; there is no way to subtract on restore.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/DailyStatistics.kt`: `splitDailyUsage`, `DailyActivity`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `StatisticsRepository.mergeRestored`, `seedSearchedTags`, `LifetimeStatistics`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsRepositories.kt`: `StatisticsTypedTagCountRecord`, `StatisticsStoreRecord`, `seedSearchedTags`, `parseHistoryDay`, `searchedTerms`
