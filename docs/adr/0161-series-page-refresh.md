# 0161. A series page refreshes from its source without losing stored data and stops refreshing while the series moves

Status: Accepted

## Context

A series page shows what is stored and refreshes from an extension source that can fail, be off or removed, or be replaced by a source switch. A refresh that finishes after a source move would store the old series again.

## Decision

- A failed refresh leaves the stored series and entries on screen. A series whose source is missing (`sourceMissing`) also keeps them; the app cannot tell why (extension off, removed or blocked, or the source's language off). Entries open through the source, or from the device without one when downloaded (`canOpen`).
- `kind` is read from the series' source id, so it is known before anything loads and while the extension is gone.
- `RefreshFailure.retryable` is false for `UnsupportedEntriesException`: trying again cannot list a series the source cannot list, so the page offers no Retry and shows the exception's text, written for the user.
- A refresh queues the new chapters for download as the followed check would, but announces none.
- `whileMoving` cancels the running refresh and refuses new ones while the move runs, and for good once it moved, because a refresh finishing after the move would store the old series again.
- Downloads are offered only for manga and only when a downloader is given; episodes do not download. A disk refusal (a full disk) is reported and changes nothing on screen.
- Each series keeps its chapter sort, direction and filter across launches (`SeriesChapterPreferences`, read once at the start unless the user has already changed them). The chapter search text and whether the search bar is open live in the view model only: they survive rotation and reset on process death.
- `userRefreshing` is separate from `refreshing` so the pull indicator is not stuck on fast failures.
- The view model takes an `ioDispatcher` for reading preferences and for filtering and sorting off the main thread; tests pass their own because on the real one the work can finish after a test and fail the next.

## Consequences

A new action that replaces the series' stored data must run inside `whileMoving`, or refreshes can overwrite it.

## Applies to

- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesPageViewModel.kt`: `SeriesPageState`, `RefreshFailure`, `refresh`, `whileMoving`, `moving`, `changeDownloads`, `canOpen`, `userRefreshing`, `chapterOptions`, `setChapterQuery`
