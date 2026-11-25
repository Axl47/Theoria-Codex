# 0219. The followed-series check reports progress in one pull pill

Status: Accepted

## Context

Pulling the Following grid checks every followed series in the selected kind filter against its source, which can take a minute. The stock
Material indicator only said that something was happening: it didn't say what releasing the pull would do, or
how much of a long check was left. Toasts for results would stack across repeated pulls.

## Decision

`checkFollowed` reports `CheckProgress(done, total)` once when it starts and again as each due series finishes.
A failed series counts as done. A series whose extension is missing is left out of the total as soon as its
lookup fails, because the Missing extensions row already counts it, and it is never counted as failed.

A pull or the failure card's Retry while a kind filter is on checks only that kind's followed series
(`SeriesTabContent.checkScope`, the same filter the grid uses), so the progress, what was new and the failure
card cover just those; with no filter it checks all. Background checks always cover every followed series.

The Series tab ViewModel holds that progress, so it lives as long as the tab and still shows the current count
after the user leaves and comes back. One pill at the top of the grid shows four states in turn: while pulling,
a label naming what the check looks for (the filtered kind's chapters or episodes; with no filter, updates when both kinds are followed) with
a ring that fills to the threshold and turns solid once armed; while checking, "N of M series checked" with a
determinate bar, floating over the grid so it stays visible while the grid scrolls; when done, "Refreshed N series" for
`FINISHED_SHOWN`, followed by what was new when anything was. N counts only the series that checked successfully in
that run, so it never contradicts the failure row; a run where every series failed shows nothing, leaving the failure
row to report it. Failures stay in the existing row inside the grid.

## Consequences

The pill takes no persistent space: it exists only while pulling, checking or briefly after a check finishes.
A check started by the background job holds the same lock, so a pull made during one shows "Checking" with an
indeterminate bar until the lock frees.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesUpdates.kt`: `CheckProgress`, `checkFollowed`, `CheckCounter`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/FollowingRefresh.kt`: `refreshPill`, `RefreshPill`, `FollowingRefreshBox`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabViewModel.kt`: `SeriesTabViewModel.refresh`, `SeriesTabContent.checkWords`, `SeriesTabContent.checkScope`

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/SeriesUpdateCheckTest.kt`: `progress counts each due series as it finishes, failures included and missing extensions left out (ADR 0219)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `aRefreshShowsHowManySeriesAreCheckedAsEachFinishesThenItsResult_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `pullingSaysWhatTheCheckLooksForAndArmsAtTheThreshold_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `thePullNamesWhatTheCheckCovers_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `aRefreshChecksOnlyTheFilteredKind_adr_0219`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/FollowingRefreshTest.kt`: `aFinishedCheckSaysHowManySeriesItRefreshedAndWhatWasNew_adr_0219`
- Left to prose: the pill floating over the grid while it scrolls, which needs a rendered screen.
