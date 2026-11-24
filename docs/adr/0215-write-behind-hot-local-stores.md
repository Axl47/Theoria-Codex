# 0215. Reading positions and statistics write behind, and tag affinity reads statistics at a steady pace

Status: Accepted

## Context

The Viewer records a reading position on every page turn and statistics on every visible post. Written through, each turn re-sorted up to `MAX_READING_POSITIONS` entries, encoded about 180 KB and swapped the file atomically: 30 fast page turns cost 30 whole-file writes on a host, and Viewer-open reads waited behind in-flight writes on the cell's lock. Each statistics record normalized the whole store, round-tripped it through its file form, validated it again and made an fsynced DataStore write (about 85 ms per view on a host with four sources at the watched-tag cap), and tag affinity regrouped the whole watched-tag table after every view.

## Decision

- A hot store wraps its durable cell with `writeBehind`. Reads and observers see the newest value immediately; the durable write runs once, `READING_POSITION_WRITE_DELAY` or `STATISTICS_WRITE_DELAY` after the first unsaved change. The timer does not restart on later changes, so continuous page turning still saves every few seconds and a crash loses at most that window.
- One write runs at a time and each writes the newest value at the moment it starts, so writes never reorder and the newest value is never dropped. A change made while a write runs schedules another write. A flush with nothing unsaved writes nothing, and a failed timed write is retried up to `WRITE_BEHIND_RETRIES` times without waiting for another change.
- Recording updates memory on the caller's thread, without a dispatcher hop and without cancellation, so a page the Viewer reported is already in memory when the stop or exit flush that follows it runs, even if the Viewer's scope is cancelled first. A record that waits for the store's first load still completes, and the store's lock orders a later flush behind it. Only the file write leaves the caller.
- Pending writes flush when the activity stops (at once, so a quick swipe from recents still finds them saved) and again on process `ON_STOP`, when the Viewer closes, before creating, switching or removing a profile, and before restore's `replace`, `merge` and `mergeRestored` return, so a cleared restore journal never outlives unsaved data. `AppUsageTracker` flushes statistics again after it records the session's usage on background. Backup reads the repositories, not the files, so it sees unsaved data.
- Reading positions and statistics are device-wide, so switching or removing a profile does not change them; the flush there only makes those moments save points too. Callers without a write scope (developer copy, acceptance checks) stay write-through; developer copy reads the file while production is in the background, after its stop flush; a flush that lands mid-copy only fails `requireUnchanged`, and the copy is retried.
- Statistics reach tag affinity through `throttleLatest` at most once per `TAG_AFFINITY_STATISTICS_PERIOD`: the first value passes at once (so `forProfile` never waits on it after launch or a profile switch), and a burst of views settles on its newest counts at the end of the period. Regrouping the capped watched-tag table on every swipe bought no visible freshness.

## Consequences

A process crash (not a normal swipe-away) can lose the last delay window of page turns and views. A flushed and reopened statistics store equals what observers saw, because the in-memory value is already normalized. Another store written on every view can adopt the same cell and add its flush to `DataDependencies.flushPendingWrites`.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/StateCell.kt`: `writeBehind`, `WriteBehindStateCell`, `WRITE_BEHIND_RETRIES`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadingPositionRepository.kt`: `READING_POSITION_WRITE_DELAY`, `FileBackedReadingPositionRepository`, `CellReadingPositionRepository.replace`, `CellReadingPositionRepository.merge`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/StatisticsRepositories.kt`: `STATISTICS_WRITE_DELAY`, `DataStoreStatisticsRepository`, `CellStatisticsRepository.mergeRestored`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/statistics/AppUsageTracker.kt`: `handleBackground`
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `throttleLatest`, `TAG_AFFINITY_STATISTICS_PERIOD`, `shareTagAffinityInputs`
- `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`: `DataDependencies.flushPendingWrites`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsViewModel.kt`: `SettingsProfileMutations.flushPendingWrites`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShellEffects.kt`, `TheoriaNavDestinations.kt`: `flushPendingWrites`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `rapid page turns write once with the newest page (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `a page turned during a write is written after it (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `stopping the app flushes so a swiped-away chapter resumes on its last page (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `cancelling during the delay keeps the last written file readable (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `restored positions are on disk before restore returns (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `a page recorded just before the Viewer closes is in the stop flush (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `a page recorded while the store is still loading survives the Viewer closing (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `a failed write is retried without another page turn and a clean flush writes nothing (ADR 0215)`
- `app/src/test/java/com/theoriacodex/app/settings/SettingsProfileWorkflowTest.kt`: `creating, switching and removing a profile flush pending writes first (ADR 0215)`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/ViewerResumeJourneyTest.kt`: `aGalleryReopenedAfterTheAppStopsResumesOnItsLastPage_adr_0215`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsWriteBehindTest.kt`: `many viewed posts write once with the newest counts (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsWriteBehindTest.kt`: `a stop flush persists exactly what observers saw (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsWriteBehindTest.kt`: `cancelling during the delay keeps the last written statistics readable (ADR 0215)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsWriteBehindTest.kt`: `restored statistics are on disk before restore returns (ADR 0215)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/ThrottleLatestTest.kt`: `the first value passes at once and a burst settles on its newest value once per period (ADR 0215)`
- `app/src/test/java/com/theoriacodex/app/statistics/AppUsageTrackerTest.kt`: `going to the background saves its usage and then flushes the store (ADR 0215)`
