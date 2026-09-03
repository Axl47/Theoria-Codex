# Working List

## In Progress

- None.

## Done

- [x] Trace the stuck-loading path and define the repair boundaries
  - Verified the spinner is owned by an active root request, historical replay can precede restoration, and HTTP `Retry-After` is uncapped.
- [x] Gate historical Search replay on route restoration
  - Verified a replay dispatched before restoration performs no provider search until restoration completes, then executes exactly once.
- [x] Bound root Search execution and provider retry delays
  - Verified a never-returning root request clears loading with a retryable error at the deadline, and excessive `Retry-After` values cap at 30 seconds.
- [x] Add focused replay and timeout regression tests
  - `RequestOwnershipSearchViewModelTest` and `DefaultSourceHttpClientTest` pass in one 65-task host-only Gradle batch.
- [x] Run bounded validation and review the final diff
  - All Search-package and HTTP transport tests pass; app/core-sources Detekt and the frozen hotspot gate pass; `SearchViewModel` is 1,099/1,100 lines.
- [x] Commit only the Search fix
  - Committed the seven task-owned files without staging the concurrent OCR and documentation changes.
