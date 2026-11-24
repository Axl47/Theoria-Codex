# 0014. Restoring a series backup only adds to what the device holds

Status: Accepted

## Context

A backup restore runs on devices that may already hold the series, further along or with a newer source list. Restoring must never take progress or state away.

## Decision

- A backup holds series that are followed, have a chosen reading mode, an opened entry, or progress (including entries the source no longer lists). A series that was only opened, never read or followed, is not the user's and is excluded.
- Entries the source stopped listing are included so one that returns is as the user left it.
- `mergeRestoredSeries` only adds: flags OR, existing reading mode, opened entry and memo win over the backup's.
- `mergeRestoredEntry` takes the further progress: read on either side, and the later page together with the page count it was recorded against. A side without a count (Mihon backups) takes the other's only if it reaches that page, so a page never exceeds its count. The later read time and the stored memo win.
- When the device already has entries, they are the source's current list; an entry only the backup has arrives as not listed: kept with progress, neither unread nor new. If it is the Continue point, the series page offers the nearest listed chapter until the next refresh.
- Restored shared payloads can hold useful offline bytes and richer resolved media, so existing posts are inserted as they are.

## Consequences

Repeated restores are idempotent and cannot regress device progress.

## Applies to

- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesDao.java`: `keptSeries`, `mergeRestoredSeries`, `mergeRestoredEntry`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomProfileBackupStore.kt`: `mergeSeries`
