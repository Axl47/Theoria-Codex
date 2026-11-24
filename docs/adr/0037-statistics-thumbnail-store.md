# 0037. Statistics thumbnails are private files owned by the top lists and fetched only for promoted or opened posts

Status: Accepted

## Context

Statistics screens show thumbnails of the posts in the Codex top lists. These must not vanish with disposable caches, and a failed download must never corrupt or interrupt statistics recording.

## Decision

- `StatisticsThumbnailStore` keeps private files owned solely by the union of all Codex top lists, outside disposable caches.
- `reconcile` fetches only explicit promotion or open candidates. A partial or failed fetch never escapes this owner; the temporary file is deleted and the next promotion or open can retry without affecting statistics recording.
- Ranking is shared (`rankedStatisticsPosts`) between the screen and the durable owner.

## Consequences

Thumbnails survive cache clearing and are removed when a post leaves every top list. Callers need no error handling for fetch failures.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsThumbnails.kt`: `StatisticsThumbnailStore`, `reconcile`, `rankedStatisticsPosts`
