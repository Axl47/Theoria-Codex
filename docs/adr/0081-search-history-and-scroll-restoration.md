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
- The grid effect (`SearchScrollRestorationEffect`) decides once per request, when it first arrives, whether the grid was already showing posts. Only a keep-live request on such a grid keeps its place; any other request (a reset to top, say) can still jump a populated grid, and a grid that was not yet showing posts is restored however many jumps it takes. The check is read without observation so scrolling neither recomposes the screen nor restarts the effect. A hidden grid that keeps its state but has no layout counts as not showing posts.
- A deliberate drag supersedes restoration (`supersedeScrollRequest`); `scrollToItem` emits no `DragInteraction`, so the effect's own jumps never do. The same close happens before Reset and tab reselect, so the posts the request waits for do not later jump the grid back to the old place. The composer's Reset closes the request first because an open request blocks persistence, which would drop the `ScrollChanged` that records the top. Opening a related-post Viewer takes over the grid the same way, so a pending restore does not jump it on return.
- Whether a restore step can run depends only on the state rules (`scrollStep` with `gridWasShowingPosts = false`), so grid facts cannot change whether a step is ready. The jump effect is keyed on the projection size because a retry needs posts that arrived, which can reach the projection after the state that loaded them. After a jump, an open request lets the list end ask for the next page, whose posts retry it.
- Persisting reports the leading item and offset from the canonical projection, because filtering can replace the post at an unchanged grid index and offset. Each persist writes saved state, so while the grid is scrolling only a new leading item is reported and the exact offset follows once it settles.
- `SearchScrollPersistenceController` has its own scheduler, independent of `viewModelScope`, so ViewModel teardown can cancel route work first and still drain the final pending position through the durable repository after the owner is gone. `reset` finishes admitted writes before resetting and holds the commit lock on the persistence dispatcher, because synchronous UI teardown may need that same lock for its final flush. `close` runs on the main thread from `ViewModel.clear()`, so it never waits on storage: the pending position is written on the controller's own scheduler, which cancels itself once that write is acknowledged, and a debounced write already in flight is not repeated. Only a process death during that one write can lose the position, and the teardown paths that kill the process skip `ViewModel.clear()` altogether.

## Consequences
Restoring never fights the user's own scrolling.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchHistory.kt`: `SearchQueryHistory`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchScrollRestoration.kt`: `allowsScrollPersistence`, `openScrollRequestId`, `canApplyScrollRestoration`, `scrollStep`, `scrollStepAfterJump`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchStateReducer.kt`: `restore`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchContract.kt`: `keepLiveGrid`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScrollEffects.kt`: `supersedeScrollRequest`, `SearchTabReselectEffect`, `resetSearchScroll`, the restoration and persistence effects
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScrollPersistenceController.kt`: `SearchScrollPersistenceController`, `reset`, `close`
