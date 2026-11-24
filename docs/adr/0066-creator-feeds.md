# 0066. Account creator feeds park their lists, and Follow records the uploads list whichever tab is shown

Status: Accepted

## Context
An account creator has several feeds (uploads, favorites and others) shown as tabs. The Viewer and other readers of `results`, `nextPageToken` and `activeRequest` must see the feed on screen. Requests never outlive a tab switch.

## Decision
- The selected feed's list lives in the top-level `CreatorUiState` fields; other feeds wait in `parkedFeeds` as `CreatorFeedState`, keeping only what the feed would show again. An empty snapshot with no `emptyReason` or `errorMessage` has not finished its first load and reloads when selected. Creators that are not accounts keep `feeds` empty and behave as one uploads list.
- `selectFeed` parks the selected feed, cancels its request and shows the parked list.
- The Favorites tab is dropped once the source reports none (private favorites count 0), and a selected Favorites tab moves to the first remaining one (`tabs`, `detailsLoaded`).
- Follow records the uploads list whichever feed is selected (`uploads`) and waits until it finished its first page (`uploadsRefreshing`), counting a parked uploads feed whose load a tab switch cancelled as unfinished.
- `creatorQueryHash` keeps the key the uploads list always had, so stored data stays reachable.

## Consequences
Tab switches are cheap and never leave stale requests. Adding a feed kind needs a parked-state and tab decision.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/creator/state/CreatorStateContract.kt`: `CreatorFeedState`, `CreatorUiState`, `tabs`, `uploads`, `uploadsRefreshing`, `openCreator`, `detailsLoaded`, `selectFeed`, `creatorQueryHash`
