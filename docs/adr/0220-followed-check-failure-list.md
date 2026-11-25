# 0220. A failed check lists its series by source and reason

Status: Accepted

## Context

"Couldn't check 5 series" didn't say which series failed or why, and the row stayed until the next check even
when retrying didn't help. Most failures come from the site (unreachable, slow, a browser check, rate limits),
not from the extension, so the list belongs on the Series tab where the check ran, not on the Extensions page.

## Decision

`checkFollowed` keeps a `CheckFailure` per failed series with its typed `SourceFailureReason`.
`ExtensionCalls.failure` reports a 403 or a browser check as `BLOCKED`, and a socket timeout or OkHttp's whole-call timeout as `TIMED_OUT`, so
extension failures reach the check typed; any other throwable is classified by its type in the check itself.
A series whose extension is missing is skipped, never a failure (the Missing extensions row counts it).

The failure row on the Series tab sits on its own card and opens a sheet that groups the failed series by
source, then by `CheckProblem`. Each group has its own Retry, which checks only those series (`only`) and
replaces just their failures in whatever failure is showing when it ends, so a failure dismissed meanwhile stays dismissed; groups over `COLLAPSE_OVER` titles start collapsed. Title rows are the Missing
extensions sheet's `FollowedTitleRow`. Dismiss clears the failure from the ViewModel only, so the next check
that fails shows it again, and nothing about it is persisted.

## Consequences

Sources that throw plain exceptions still land in a sensible group. A new `SourceFailureReason` value has to be
placed in `CheckProblem` because the mapping is an exhaustive `when`.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesUpdates.kt`: `CheckFailure`, `FollowedCheck.failures`, `checkFollowed`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCalls.kt`: `ExtensionCalls.failure`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/CheckFailures.kt`: `CheckProblem`, `bySource`, `CheckFailedCard`, `CheckFailuresSheet`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabViewModel.kt`: `SeriesTabViewModel.retry`, `SeriesTabViewModel.dismissFailures`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionCallsTest.kt`: `a failure says whether the site was unreachable, slow, refusing or limiting (ADR 0220)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/SeriesUpdateCheckTest.kt`: `a failed series keeps why it failed, and a retry checks only the series it names (ADR 0220)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `dismissingTheFailureHidesItUntilAnotherCheckFails_adr_0220`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `retryingAGroupChecksOnlyItsSeriesAndKeepsTheOtherFailures_adr_0220`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabRefreshTest.kt`: `dismissingDuringAGroupRetryKeepsOnlyWhatTheRetryFound_adr_0220`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/CheckFailuresTest.kt`: `failuresGroupBySourceThenReasonAndDropSeriesNoLongerFollowed_adr_0220`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/CheckFailuresTest.kt`: `theFailureCardOpensTheListRetriesAGroupAndDismisses_adr_0220`
