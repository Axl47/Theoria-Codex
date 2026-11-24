# 0081. Search history keeps settled searches only, and scroll restoration yields to the user

Status: Accepted

## Context
Undo and redo step through searches the user settled on, and returning to a search should restore its scroll place without jumping a grid the user already controls.

## Decision
- `SearchQueryHistory` holds only settled searches; live intermediates never enter. A different search moves `current` back, bounded to `LIMIT`, and drops forward steps. An unsettled search on screen leaves no trace when stepping back. The restored stored search counts as settled so the next one can step back to it.
- Scroll persistence is allowed only for a restored grid with no open scroll request, so initial layout and programmatic restoration never replace the saved anchor.
- An open request is superseded by a deliberate scroll or by opening a post, which shows the user has taken over the grid. A keep-live request (resuming a live grid) must not replace an open request that has to move the grid, such as the startup restore or a reset to top. Whether the grid was showing posts is decided once when the request arrives, so the next page still retries a stopped-short jump.
- `scrollStep` rules: wait while no search finished or one loads, a failure shows, the anchor's page is not loaded and more can load, or its visibility is undecided; a keep-live request on a grid already showing posts finishes without moving, since a jump aligns every column at the target and the grid later reshuffles. An absent anchor is final only after the continuation is exhausted. Only rules 1-6 wait.
- `scrollStepAfterJump` waits when the jump stopped at the list end above the target; the next page's posts retry it.

## Consequences
Restoring never fights the user's own scrolling.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchHistory.kt`: `SearchQueryHistory`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchScrollRestoration.kt`: `allowsScrollPersistence`, `openScrollRequestId`, `canApplyScrollRestoration`, `scrollStep`, `scrollStepAfterJump`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchStateReducer.kt`: `restore`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchContract.kt`: `keepLiveGrid`
