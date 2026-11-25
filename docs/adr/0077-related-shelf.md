# 0077. Related shelves anchor to their seed, keep their seeds visible, and are never restored

Status: Accepted

## Context
A related shelf opens inside a feed list that can reorder under it (a length sort, Recents, late duration arrivals), and its recommendation chain continues until dismissed.

## Decision
- `anchorIndexIn` places the shelf after its seed, else after the first earlier seed of its chain still listed, else after its anchor index; -1 when empty. The projection follows the seed when canonical order changes under an open shelf.
- Late duration arrivals sit below posts with higher canonical indices, so position order is not canonical order: `nearestAtOrBefore` finds the closest post at or before a canonical index, and only canonical post entries can replace the last persisted scroll position.
- Related streams are never restored: `relatedViewerContext` launches a Viewer bound to the feed.
- Seeds stay visible until the recommendation chain is dismissed, including after viewing or automatic saving (`retainedLikedPostIds`, `filterSearchResults`). The drawer only grows: everything shown, then anything newly recommended; liked seeds leave and a cleared shelf empties it (`accumulatedPosts`).
- `RelatedPostsRouteController.show` first promotes a post inside the shelf into the feed, so the new shelf chains below it.
- `ViewerFeedOwner.retryPage` asks again for a failed page, which `loadNextPage` may refuse; owners whose `loadNextPage` already repeats a failed page need not override it.
- `RelatedPostsDiagnostics` logs sparse lifecycle only: no post IDs, tags, media locations or account data.

## Consequences
Feed filters must keep retained seeds reachable.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/related/RelatedPostsState.kt`: `anchorIndexIn`, `relatedViewerContext`, `retainedLikedPostIds`, `accumulatedPosts`
- `app-logic/src/main/kotlin/com/theoriacodex/app/related/RelatedFeedProjection.kt`: `canonicalPositionForGridIndex`, `nearestAtOrBefore`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchVisibilityFilters.kt`: `filterSearchResults`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/related/RelatedPostsRouteController.kt`: `show`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/related/ViewerFeedOwner.kt`: `retryPage`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/related/RelatedPostsDiagnostics.kt`: `RelatedPostsDiagnostics`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/related/RelatedPostsStateTest.kt`: `a shelf follows its seed, then its chain, when the list reorders under it (ADR 0077)`, `accumulated posts add new recommendations instead of replacing them (ADR 0077)`, `accumulated posts drop liked seeds and clear with the shelf (ADR 0077)`, `hide liked retains shelf seeds through loading results empty and failure until dismissal (ADR 0077)`, `promotion inserts once and removes the promoted card from retained pages (ADR 0077)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/related/RelatedFeedProjectionTest.kt`: `visible seed inserts shelf after seed without changing canonical indices (ADR 0077)`, `hidden seed anchors after nearest visible predecessor (ADR 0077)`, `shelf follows its seed when the canonical order changes under it (ADR 0077)`, `a late arrival below its neighbours does not pull the shelf away from its seed (ADR 0077)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchVisibilityFiltersTest.kt`: `watched liked seed stays visible until recommendations are dismissed (ADR 0077)`, `pending like retains its automatic save without retaining unrelated saved posts (ADR 0077)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/related/RelatedPostsRouteControllerTest.kt`: `liking a shelf post promotes it and opens a retained next page (ADR 0077)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/related/RelatedPostsDiagnosticsTest.kt`: `a lifecycle line names no post, tag, address or account (ADR 0077)`
- Left to prose: that related streams are never restored, and each feed owner's `retryPage`, are not yet guarded; the first is decided by the Viewer route in `:app` and testable beside `ViewerRoutePolicyTest`, the second in each owner's feature module, beside tests such as `SearchPageRecoveryTest` and `ForYouDeckViewModelTest`.
