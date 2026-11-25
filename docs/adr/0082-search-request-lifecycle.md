# 0082. Search requests end through one reducer that keeps results on screen and ignores foreign answers

Status: Accepted

## Context
Searches run live while the user edits, may answer partially, and may reuse a cached first page. A late or failed answer must not flicker, replace the wrong search, or lose the user's place.

## Decision
- Every search is live, so results on screen stay until the next search's results replace them (`retainsResultsWhileRefreshing`).
- A new search clears the continuation; a page keeps it. A page belongs to the results on screen only while they are the search it was asked for, otherwise the request is cancelled. A failed page keeps continuation and retried source so Retry repeats it.
- A search that never answered still becomes the applied one, so Retry repeats it, and it is recorded for its mode; an answer that reports failure counts as a search that never answered. A temporary source pick becomes the applied search the same way but is never recorded for its mode. A cached first page shown before the answer arrives is not recorded for its mode (`recordForMode = false`), so the mode's entry stays as the preceding search left it, which may be a failure or a partial result.
- The applied query may be a normalized form of what was asked; it is kept as `shownQuery`.
- While a cached first page shows (`CachedFirstPage.SHOWN`), partial results wait for the complete answer to avoid flicker. After the user scrolls it (`SCROLLED`), shown posts stay put and new ones go below unless they copy a post above (`refreshed`); a fresh post takes the cached picture hash when its own missed the listing's wait for the same preview.

## Consequences
Partial results can only show on an empty or unscrolled grid.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchStateReducer.kt`: `SearchStateReducer`, `refreshed`, `withPictureHashOf`, `withFailedRequest`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchContract.kt`: `retainsResultsWhileRefreshing`, `CachedFirstPage`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchRequestReducerTest.kt`: `begin loading clears old error while retaining visible content (ADR 0082)`, `a new search drops what the old results continued from and a page request keeps it (ADR 0082)`, `a page for a search no longer on screen is cancelled (ADR 0082)`, `a search that never answers becomes the applied search so Retry repeats it (ADR 0082)`, `a temporary search that never answers becomes the applied search without replacing the mode's last one (ADR 0082)`, `a cached first page shown before the answer leaves the mode's last search in place (ADR 0082)`, `page failure merges provider status and leaves the search and its retry in place (ADR 0082)`, `after early results an untouched draft follows whichever query the request ends on (ADR 0082)`, `a cached first page holds back early results and is not yet the mode's search (ADR 0080, 0082)`, `a scrolled cached page keeps its posts in place when the fresh ones arrive (ADR 0082)`, `a refresh that fails leaves the cached first page on screen (ADR 0082)`
