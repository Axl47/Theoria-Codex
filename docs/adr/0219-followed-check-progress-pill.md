# 0219. The followed-series check reports progress in one pull pill

Status: Accepted

## Context

Pulling the Following grid checks every followed series against its source, which can take a minute. The stock
Material indicator only said that something was happening: it didn't say what releasing the pull would do, or
how much of a long check was left. Toasts for results would stack across repeated pulls.

## Decision

`checkFollowed` reports `CheckProgress(done, total)` once when it starts and again as each due series finishes.
A failed series counts as done. A series whose extension is missing is left out of the total as soon as its
lookup fails, because the Missing extensions row already counts it, and it is never counted as failed.

The Series tab ViewModel holds that progress, so it lives as long as the tab and still shows the current count
after the user leaves and comes back. One pill at the top of the grid shows four states in turn: while pulling,
a label naming what the check looks for (chapters, episodes, or updates when both kinds are followed, whatever kind filter is on, since the check covers every followed series) with
a ring that fills to the threshold and turns solid once armed; while checking, "N of M series checked" with a
determinate bar, floating over the grid so it stays visible while the grid scrolls; when done, what was new for
`FOUND_SHOWN`, or nothing. Failures stay in the existing row inside the grid.

## Consequences

The pill takes no persistent space: it exists only while pulling, checking or briefly after new entries arrive.
A check started by the background job holds the same lock, so a pull made during one shows "Checking" with an
indeterminate bar until the lock frees.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesUpdates.kt`: `CheckProgress`, `checkFollowed`, `CheckCounter`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/FollowingRefresh.kt`: `refreshPill`, `RefreshPill`, `FollowingRefreshBox`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabViewModel.kt`: `SeriesTabViewModel.refresh`, `SeriesTabContent.checkWords`

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/SeriesUpdateCheckTest.kt`: `progress counts each due series as it finishes, failures included and missing extensions left out (ADR 0219)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `aRefreshShowsHowManySeriesAreCheckedAsEachFinishesThenWhatWasNew_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `pullingSaysWhatTheCheckLooksForAndArmsAtTheThreshold_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `thePullNamesWhatTheCheckCoversWhicheverKindIsFiltered_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `aRunningCheckShowsItsProgressAndAFinishedOneOnlyWhatWasNew_adr_0219`
- Left to prose: the pill floating over the grid while it scrolls, which needs a rendered screen.
