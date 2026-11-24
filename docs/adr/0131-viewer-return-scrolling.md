# 0131. Returning from the Viewer moves the feed as little as possible and keeps the originating post in place

Status: Accepted

## Context

Closing the Viewer must land on the post it left without reshuffling the staggered grid, covering heavy startup work, or rerunning a changing feed. User decision 2026-11-16: keep the feed where it was rather than centring the card.

## Decision

- `viewerReturnRevealScroll` scrolls only as far as needed for the card to be fully visible: 0 if visible, align start if larger than the viewport, else the nearer edge, pinned by `arrivedFrom`. The visible span excludes content padding; floating controls do not count as covering.
- An offscreen card is reached by relative scrolls, not `scrollToItem`, which lines every column up and forces a later reshuffle; direction comes from index order (`viewerReturnScrollDirection`); a far target (over `MAX_VIEWER_RETURN_SCROLL_STEPS`) falls back to a jump. Lazy lists jump to an offscreen item first.
- Shelf and carousel returns need both axes positioned (`shelfPositioned`); Recents' timeline matches by post since viewing regroups rows.
- While closing media settles (`mediaSettling`), the returning feed holds its heaviest startup work; hide policies resume only after the Viewer route is disposed (`retainPostsUntilViewerCloses`).
- The restoration request carries the exact bounded launch order so sorting and filtering are captured without rerunning the feed; `ViewerSessionIdentity` rejects late work from a replaced session.

## Consequences

The return effect is shared by every grid, shelf and column.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnContract.kt`: `viewerReturnRevealScroll`, `scrollWithinColumnsTo`, `ViewerReturnTransition`, `ViewerReturnShelfEffect`, `ViewerReturnColumn`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRestoration.kt`: `ViewerRestorationRequest`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerSessionIdentity.kt`: `ViewerSessionIdentity`
