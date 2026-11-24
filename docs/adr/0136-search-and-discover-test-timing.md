# 0136. Search and Discover tests step virtual time by hand so timeouts, undo offers and idling do not fire early

Status: Accepted

## Context

The Search and Discover owners use real timeouts (a request's root timeout, the undo offer's lifetime, a provider lookup timeout) and Compose dialogs that keep Robolectric busy. A test helper that advances time until idle runs those clocks to their end and changes what the test observes.

## Decision

- Tests that hold a request open step with `runCurrent()`, not `advanceUntilIdle()` or `restore()`, because advancing until idle would run the held request into its timeout and end it (`AdmissionFailureSearchViewModelTest`, `SearchLoadingResponsivenessTest`).
- `CreatorFollowsViewModelTest` uses `runCurrent()` where advancing time would expire the Undo offer under test.
- `CreatorProfileCoordinator` takes its `providerContext` as a parameter (default `Dispatchers.Default`); its tests pass `EmptyCoroutineContext` so provider calls stay on the test dispatcher and virtual time drives them. A workflow whose lookup runs on a background dispatcher (`CreatorOpeningWorkflowTest`, Gelbooru) needs its timeout to run in real time, so that test runs under `Dispatchers.Default`.
- Robolectric runs main-thread delays, request timeouts included, only as its clock moves, so `SearchUndoScrollTest` idles the main looper in steps. `SearchComposerUiTest` uses a tall window for its saved rows but the name-dialog test overrides to a small window (`w320dp-h470dp`), because on the tall window Robolectric never idles once the dialog takes text.

- `CreatorRelatedPostsScreenTest` checks the heart right after the tap, while the like is still pending, because Room membership can reach the grid before the commit callback starts the shelf. Releasing the seed changes the feed's filter inputs, which refilter off the main thread, so it waits for the result with `waitUntilSettled` instead of idling.

## Consequences

These tests are deliberately slow-clock tests; replacing the helpers with advance-until-idle shortcuts changes outcomes rather than speeding them up.

## Applies to

- `feature-search/src/test/kotlin/com/theoriacodex/app/search/AdmissionFailureSearchViewModelTest.kt`: `AdmissionFailureSearchViewModelTest`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchLoadingResponsivenessTest.kt`: `SearchLoadingResponsivenessTest`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchUndoScrollTest.kt`: `SearchUndoScrollTest`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchComposerUiTest.kt`: `SearchComposerUiTest`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModelTest.kt`: `CreatorFollowsViewModelTest`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorOpeningWorkflowTest.kt`: `CreatorOpeningWorkflowTest`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorProfileCoordinatorTest.kt`: `CreatorProfileCoordinatorTest`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorRelatedPostsScreenTest.kt`: `CreatorRelatedPostsScreenTest`, `waitUntilSettled`
