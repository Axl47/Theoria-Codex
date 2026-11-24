# 0082. Search requests end through one reducer that keeps results on screen and ignores foreign answers

Status: Accepted

## Context
Searches run live while the user edits, may answer partially, and may reuse a cached first page. A late or failed answer must not flicker, replace the wrong search, or lose the user's place.

## Decision
- Every search is live, so results on screen stay until the next search's results replace them (`retainsResultsWhileRefreshing`).
- A new search clears the continuation; a page keeps it. A page belongs to the results on screen only while they are the search it was asked for, otherwise the request is cancelled. A failed page keeps continuation and retried source so Retry repeats it.
- A search that never answered still becomes the applied one so Retry repeats it, but is not recorded for its mode (`recordForMode = false`).
- The applied query may be a normalized form of what was asked; it is kept as `shownQuery`.
- While a cached first page shows (`CachedFirstPage.SHOWN`), partial results wait for the complete answer to avoid flicker. After the user scrolls it (`SCROLLED`), shown posts stay put and new ones go below unless they copy a post above (`refreshed`); a fresh post takes the cached picture hash when its own missed the listing's wait for the same preview.

## Consequences
Partial results can only show on an empty or unscrolled grid.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchStateReducer.kt`: `SearchStateReducer`, `refreshed`, `withPictureHashOf`, `withFailedRequest`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchContract.kt`: `retainsResultsWhileRefreshing`, `CachedFirstPage`
