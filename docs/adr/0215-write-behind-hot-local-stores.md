# 0215. Hot local stores write behind and flush when the app stops, the Viewer closes or a restore lands

Status: Accepted

## Context

The Viewer records a reading position on every page turn. Written through, each turn re-sorted up to `MAX_READING_POSITIONS` entries, encoded about 180 KB and swapped the file atomically: 30 fast page turns cost 30 whole-file writes on a host, and Viewer-open reads waited behind in-flight writes on the cell's lock.

## Decision

- A hot store wraps its durable cell with `writeBehind`. Reads and observers see the newest value immediately; the durable write runs once, `READING_POSITION_WRITE_DELAY` after the first unsaved change. The timer does not restart on later changes, so continuous page turning still saves every few seconds and a crash loses at most that window.
- One write runs at a time and each writes the newest value at the moment it starts, so writes never reorder and the newest value is never dropped. A change made while a write runs schedules another write. A flush with nothing unsaved writes nothing, and a failed timed write is retried up to `WRITE_BEHIND_RETRIES` times without waiting for another change.
- Recording updates memory on the caller's thread, without a dispatcher hop, so a page the Viewer reported is already in memory when the stop or exit flush that follows it runs, even if the Viewer's scope is cancelled first. Only the file write leaves the caller.
- Pending writes flush when the activity stops (at once, so a quick swipe from recents still finds them saved) and again on process `ON_STOP`, when the Viewer closes, after switching or removing a profile, and before restore's `replace` and `merge` return, so a cleared restore journal never outlives unsaved positions. Backup reads the repository, not the file, so it sees unsaved positions.
- Reading positions are one device-wide file, so switching or removing a profile does not change them; the flush there only makes those moments save points too. Callers without a write scope (developer copy, acceptance checks) stay write-through; developer copy reads the file while production is in the background, after its stop flush; a flush that lands mid-copy only fails `requireUnchanged`, and the copy is retried.

## Consequences

A process crash (not a normal swipe-away) can lose the last delay window of page turns. Other stores that are written on every view can adopt the same cell and add their flush to `DataDependencies.flushPendingWrites`.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/StateCell.kt`: `writeBehind`, `WriteBehindStateCell`, `WRITE_BEHIND_RETRIES`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadingPositionRepository.kt`: `READING_POSITION_WRITE_DELAY`, `FileBackedReadingPositionRepository`, `CellReadingPositionRepository.replace`, `CellReadingPositionRepository.merge`
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
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionWriteBehindTest.kt`: `a failed write is retried without another page turn and a clean flush writes nothing (ADR 0215)`
- `app/src/test/java/com/theoriacodex/app/settings/SettingsProfileWorkflowTest.kt`: `switching and removing a profile flush pending writes (ADR 0215)`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/ViewerResumeJourneyTest.kt`: `aGalleryReopenedAfterTheAppStopsResumesOnItsLastPage_adr_0215`
