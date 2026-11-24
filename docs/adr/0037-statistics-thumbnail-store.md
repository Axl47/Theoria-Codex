# 0037. Statistics thumbnails are private files owned by the top lists and fetched only for promoted or opened posts

Status: Accepted

## Context

Statistics screens show thumbnails of the posts in the Codex top lists. These must not vanish with disposable caches, and a failed download must never corrupt or interrupt statistics recording.

## Decision

- `StatisticsThumbnailStore` keeps private files owned solely by the union of all Codex top lists, outside disposable caches.
- `reconcile` fetches only explicit promotion or open candidates. A partial or failed fetch never escapes this owner; the temporary file is deleted and the next promotion or open can retry without affecting statistics recording.
- Ranking is shared (`rankedStatisticsPosts`) between the screen and the durable owner.
- `ThumbnailStatisticsRepository` runs the thumbnail work application-owned; observing Stats only reads the resulting local paths. It needs metadata only for recently available posts (a bounded most-recent set) and never performs a provider lookup.
- Snapshots that change only counters outside Codex post rankings are skipped, and a post outside the top lists needs no disk work. Reconciliation waits until the first snapshot, because promotions may not be known yet and reconciling earlier would delete every retained thumbnail. Each fetch times out after 15 s.

## Consequences

Thumbnails survive cache clearing and are removed when a post leaves every top list. Callers need no error handling for fetch failures.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsThumbnails.kt`: `StatisticsThumbnailStore`, `reconcile`, `rankedStatisticsPosts`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/statistics/ThumbnailStatisticsRepository.kt`: `ThumbnailStatisticsRepository`
