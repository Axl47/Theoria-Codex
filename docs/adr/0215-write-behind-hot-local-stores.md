# 0215. Hot local stores write behind and flush when the app stops, the Viewer closes or a restore lands

Status: Accepted

## Context

The Viewer records a reading position on every page turn. Written through, each turn re-sorted up to `MAX_READING_POSITIONS` entries, encoded about 180 KB and swapped the file atomically: 30 fast page turns cost 30 whole-file writes on a host, and Viewer-open reads waited behind in-flight writes on the cell's lock.

## Decision

- A hot store wraps its durable cell with `writeBehind`. Reads and observers see the newest value immediately; the durable write runs once, `READING_POSITION_WRITE_DELAY` after the first unsaved change. The timer does not restart on later changes, so continuous page turning still saves every few seconds and a crash loses at most that window.
- One write runs at a time and each writes the newest value at the moment it starts, so writes never reorder and the newest value is never dropped. A change made while a write runs schedules another write.
- Pending writes flush on process `ON_STOP` (a swipe from recents follows it), when the Viewer closes, and before restore's `replace` and `merge` return, so a cleared restore journal never outlives unsaved positions. Backup reads the repository, not the file, so it sees unsaved positions.
- Reading positions are one device-wide file, so switching or deleting a profile leaves them alone. Callers without a write scope (developer copy, acceptance checks) stay write-through; developer copy reads the file while production is in the background, after its `ON_STOP` flush.

## Consequences

Guarded by `ReadingPositionWriteBehindTest`: rapid page turns write once with the newest page, a turn during a write is written after it, the stop flush lets a reopened store resume on the last page, cancelling mid-delay leaves the previous file readable, and restores are on disk before they return. A process crash (not a normal swipe-away) can lose the last delay window of page turns. Other stores that are written on every view can adopt the same cell and add their flush to `DataDependencies.flushPendingWrites`.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/StateCell.kt`: `writeBehind`, `WriteBehindStateCell`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadingPositionRepository.kt`: `READING_POSITION_WRITE_DELAY`, `FileBackedReadingPositionRepository`, `CellReadingPositionRepository.replace`, `CellReadingPositionRepository.merge`
- `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`: `DataDependencies.flushPendingWrites`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShellEffects.kt`, `TheoriaNavDestinations.kt`: `flushPendingWrites`
