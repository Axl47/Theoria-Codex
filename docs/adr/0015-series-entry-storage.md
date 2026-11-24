# 0015. Series entries are kept, never deleted, when a source stops listing them

Status: Accepted

## Context

Sources re-list chapters, change addresses, or stop listing them. The user's read marks and progress must survive, and a chapter that returns must not appear as new or unread.

## Decision

- An entry the source stopped listing keeps its row and progress with a negative `source_order` (`NOT_LISTED`, -1). It is hidden from lists and counts; only `allEntries` returns it.
- An entry with no upload date counts from when it was first seen, so a new one is never passed over by the newest-entry computation.
- Series rows are updated with `@Upsert` in place, because replacing the row would cascade its entries away. The series row is written before its entries, which refer to it.
- `last_page`/`page_count` store a page from 1 or milliseconds for an episode; the page-named columns stay because renaming needs a schema upgrade for nothing.
- A listing reports new entries only when stored entries exist to compare with and at least one listed entry matches a stored one by address or as a moved entry. Otherwise the list is a baseline (the source probably changed addresses). A source listing only its newest chapter never reports a new one.
- A moved entry continues a dropped one only when each is the only one of its release (chapter number plus translator group) among its kind, so one group's read chapter is never handed to another group's release.
- An entry listed without a memo keeps the stored memo; a series keeps stored description, status and memo that a listing or sparse details omit.
- Unchanged rows are not written, so observers are not woken by reopening a chapter where it was left. Moving a series to another source counts all stored entries, including unlisted ones, since their progress moves with it.

## Consequences

Sources that only list one chapter, or change addresses wholesale, never produce new-chapter notices. Revisiting requires changing the baseline rule in `storeEntries`.

## Applies to

- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomSeriesRepository.kt`: `NOT_LISTED`, `storeEntries`, `movedEntries`, `release`, `keeping`, `recordProgress`, `moveSeries`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesDao.java`: `upsertSeries`, `allEntries`, newest-entry subquery
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesEntryEntity.java`: `lastPage`
