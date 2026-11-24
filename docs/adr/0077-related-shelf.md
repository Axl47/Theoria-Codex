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
