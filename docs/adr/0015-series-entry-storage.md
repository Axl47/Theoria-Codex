# 0015. Series entries are kept, never deleted, when a source stops listing them

Status: Accepted

## Context

Sources re-list chapters, change addresses, or stop listing them. The user's read marks and progress must survive, and a chapter that returns must not appear as new or unread.

## Decision

- An entry the source stopped listing keeps its row and progress with a negative `source_order` (`NOT_LISTED`, -1). It is hidden from the listed-entry flow and query, unread counts and the newest-entry computation; `allEntries` is the only list query that returns it. Direct lookup by address (`SeriesDao.entry`, used by `recordProgress`) also returns it, so progress on an unlisted chapter is still recorded; `listedEntry` filters it out.
- An entry with no upload date counts from when it was first seen, so a new one is never passed over by the newest-entry computation.
- Series rows are updated with `@Upsert` in place, because replacing the row would cascade its entries away. The series row is written before its entries, which refer to it.
- `last_page`/`page_count` store a page from 1 or milliseconds for an episode; the page-named columns stay because renaming needs a schema upgrade for nothing.
- A listing reports new entries only when stored entries exist to compare with and at least one listed entry matches a stored one by address or as a moved entry. Otherwise the list is a baseline (the source probably changed addresses). A source listing only its newest chapter never reports a new one.
- A followed series' first listing, with no entries stored, reports the entries its source dates after the series was followed, less `DATE_SLACK_MS` because many sources date by day. Older and undated entries are its baseline. So a series followed while its source listed nothing hears of its first chapters, while a quiet failure (an empty list) leaves nothing behind that a later listing could mistake for news: old chapters carry old dates. A followed series imported without entries counts from the backup's date added.
- A moved entry continues a dropped one only when its release (chapter number plus translator group; an entry with no number never matches) is carried by exactly one newly listed entry and exactly one dropped entry, and no retained address (one listed under an address already stored) now reports that same release. The last condition is a veto: if a retained entry still claims the release, the new entry is a new chapter, so one group's read chapter is never handed to another group's release.
- An entry listed without a memo keeps the stored memo; a series keeps stored description, status and memo that a listing or sparse details omit.
- `recordProgress` writes nothing when the chapter is already the opened one and its progress is unchanged, so observers are not woken by reopening a chapter where it was left. A listing refresh (`storeEntries`) upserts the series row and every current entry each time it stores entries. Moving a series to another source counts all stored entries, including unlisted ones, since their progress moves with it.
- **The background check paces each followed series by its releases** (#339). The job used to list every followed series on every run, though most of a long-lived library is finished or on hiatus. A series is now due once its wait has passed since `series.checked_at_epoch_ms`. The wait is the larger of the median gap between its newest ten listed releases and the time since its newest release, divided by `CHECKS_PER_RELEASE_GAP` (24), at most `SERIES_CHECK_CEILING` (7 days). Only upload dates count: first-seen times record when a check happened to look, and a whole first listing shares one. Fewer than three listed entries, or an undated one among the newest ten, give no pace: checked every run.
- A run skips a series only while the next run would still fall inside its wait: due once `elapsed + runEvery > wait`, `runEvery` being the user's interval. A wait up to the interval means every run, a check by hand between runs never makes the job overshoot the wait, and checks are never further apart than the ceiling while the system runs the job on time. The ceiling test needs no entries, so a series never checked or past it is decided without loading them.
- Any stored listing with entries sets the check time: the job's, the Following refresh, the series page, a move's target. A source failure, a missing extension or an empty listing leaves it, so the series stays due; following never sets it; a series never checked, restored from a backup (which doesn't carry the time) or imported is due. Only the job passes `runEvery`: checks by hand list every followed series.
- The job requires a battery that is not low, and does nothing if it runs while the interval is Off; a pending job scheduled without that condition is scheduled again with it at the next app start. Its diagnostics line counts the series checked and not due, and the run's duration.

## Consequences

Sources that only list one chapter, or change addresses wholesale, never produce new-chapter notices. A source that gives no dates keeps a silent first listing. Revisiting requires changing the baseline rule in `storeEntries`.

A chapter from a series that breaks its pace is announced late: by up to a twenty-fourth of its usual gap while it keeps time, by up to a week after a long silence. A source that dates new chapters stale makes an active series look quiet, and a source without dates saves nothing. In a simulated four weeks at a 6-hour interval with 50 mixed series (4 daily, 13 weekly, 6 monthly, 3 irregular, 20 quiet for months, 2 back from hiatus, 2 one-shots), requests fell from 5,650 to 2,581, daily and weekly series were still listed on every run and announced as fast as before, and no chapter waited past the ceiling (`SeriesCheckPaceTest`). Tune `CHECKS_PER_RELEASE_GAP` for latency against requests, `SERIES_CHECK_CEILING` for the longest silence. A schema 21 column with a similar name was dropped because nothing read it; this one is read by every background run.

## Applies to

- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomSeriesRepository.kt`: `NOT_LISTED`, `storeEntries`, `datedAfterFollowing`, `movedEntries`, `release`, `keeping`, `recordProgress`, `moveSeries`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesDao.java`: `upsertSeries`, `allEntries`, newest-entry subquery
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesEntryEntity.java`: `lastPage`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesCheckSchedule.kt`: `checkWait`, `isCheckDue`, `CHECKS_PER_RELEASE_GAP`, `SERIES_CHECK_CEILING`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesUpdates.kt`: `checkFollowed`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/TheoriaRoomDatabase.java`: `MIGRATION_22_23`
- `app/src/main/java/com/theoriacodex/app/series/SeriesUpdateSchedule.kt`: `scheduleSeriesUpdates`
- `app/src/main/java/com/theoriacodex/app/series/SeriesUpdateJobService.kt`
