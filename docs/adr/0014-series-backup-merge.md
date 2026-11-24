# 0014. Restoring a series backup only adds to what the device holds

Status: Accepted

## Context

A backup restore runs on devices that may already hold the series, further along or with a newer source list. Restoring must never take progress or state away.

## Decision

- A backup holds series that are followed, have a chosen reading mode, an opened entry, or progress (including entries the source no longer lists). A series that was only opened, never read or followed, is not the user's and is excluded.
- Entries the source stopped listing are included so one that returns is as the user left it.
- `mergeRestoredSeries` only adds: flags OR, existing reading mode, opened entry and memo win over the backup's.
- `mergeRestoredEntry` takes the further progress: read on either side, and the later page together with the page count it was recorded against. A side without a count (Mihon backups) takes the other's only if it reaches that page, so a page never exceeds its count. The later read time and the stored memo win.
- When the device already holds entries for the series (listed or not), the device's rows reflect the source's current list; an entry only the backup has arrives as not listed: kept with progress, neither unread nor new. Continue (`continueEntry`) works from the listed entries only, so if the restored opened entry is not listed it is not found; Continue then falls back to the latest listed entry the user is partway through, else the first unread entry in reading order, else the last entry, until a refresh lists it again.
- Restoring inserts posts the device lacks and ignores conflicts (`insertPost`), so a post already on the device keeps its stored payload, including its offline paths and resolved media; a backup's payload never replaces it.

## Consequences

Repeated restores are idempotent and cannot regress device progress.

## Applies to

- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesDao.java`: `keptSeries`, `mergeRestoredSeries`, `mergeRestoredEntry`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomProfileBackupStore.kt`: `merge`, `mergeSeries`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/CodexLikesDao.java`: `insertPost`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesRepository.kt`: `continueEntry`
