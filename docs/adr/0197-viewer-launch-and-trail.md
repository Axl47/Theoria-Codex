# 0197. Every Viewer launch goes through launchViewer, and each caller states whether it saves context or reuses the top Viewer

Status: Accepted

## Context

The Viewer is opened from feeds, Codices, the deck, shared links, downloads and statistics. A Viewer can stack in a trail (a fork opens above its parent), and the single Creator owner can only serve one page. A process death must restore a Viewer's place only for launches whose feed can be rebuilt. A like or a tag edit made in the Viewer must not disturb the feed behind it.

## Decision

- `launchViewer` is where every launch ends: the session is retained for the Viewer destination to claim, then the Viewer is navigated to. Launches differ in two stated ways. `saveContext` saves the launch context so a Viewer recreated after process death restores its place from it; Search saves its own when it opens a result, and Creator, related, For You and series launches leave the saved context as it was. `singleTop` lets a launch reuse a Viewer already on top instead of stacking another.
- Search uses `saveContext = false` and `singleTop = !liveSearchBinding` (a launch that will not follow the feed reuses a Viewer on top). A typed post id was never searched, so saving it to a Codex records no search (`saveQuery` is dropped for `fromTypedId`). Creator pages save no launch context and reuse a Viewer on top when the launch will not follow the feed.
- For You: the deck's writes run in the activity's scope (the hand-off ViewModel's `viewModelScope`), so they finish after the tab or the deck is gone. `ForYouDeckWiring.trails` is the deck's one way to the app's trails: a fork's level and the posts a like asks for both come from it, with the deck's own media filter. `openForYouPost` opens the Viewer on a post of a level, which keeps following that level's feed while it is on top; nothing is saved for restore since the deck does not outlive its owner, and the feed's filters are not reapplied because the deck's owner already applied For You's.
- `openViewerSession` replaces any open Viewer, with every level of its trail, with a fresh session. It pops until the top is no longer a Viewer, because popping to the route alone would leave the levels under the top one, and it teaches the file-mirror index the posts.
- A shared link (`openExternalPost`) leaves an open Viewer underneath and reuses one on top, unlike other one-post launches, which replace it. `openOriginalPost` replaces the open Viewer only for a post on an active source; anything else, or a post that cannot be loaded, opens in the browser.
- `openCreatorProfile` opens the creator above the current screen so Back returns to it. A Viewer stays open underneath, except one browsing a creator page's feed: that feed lives in the single creator owner the new page takes over, so the Viewer is closed. A creator opened from a creator page replaces it, and each creator entry remembers the creator it started with, so Back through Creator, Viewer and Creator returns to the right page.
- Trail navigation: media owns the expansion and shrink, and navigation retains both destinations. A trail fork fades over the Viewer it covers, and any trail level, also one opened from a grid, fades out as it closes. Viewer-to-Viewer navigation and a trail level's close hold no card return, and the level going under stops at once, so two levels never play side by side (`ViewerReturnLifetime`, `ViewerCoveredLifecycle`). Each entry withdraws only its own owner from the route slot, never the one on top of or under it.
- Tag actions are built once per shell and level (`viewerTagSearchActions`), so a Search, For You or Creator state change leaves the Viewer's controls as they are. Every tag sheet in the Viewer stages its edits (`LocalPostTagSearchActions`), not only the include and exclude controls. Staging goes to the Search draft, so Search does not rerun behind the Viewer and replace the card it returns to; running the draft (`applyDraft`) still runs it.
- `toggleLikeAndSyncCodex` writes the like to the profile that was active at the tap, even if the profile changes meanwhile; only the feed-update callback is skipped when that profile is no longer active. `feedLikeAction` binds a like to the feed behind its source and the query it shows now: a committed like opens or clears that feed's related shelf, unless the feed has since moved to another query or owner.
- `openStatisticsPost` uses a saved copy when there is one, so it works offline; the source is asked only for posts no longer saved. `previewStatisticsPost` never saves a looked-up post, so a friend's stats leave nothing behind in this library.

## Consequences

A new launcher must pick `saveContext` and `singleTop` deliberately. Because For You and related Viewers are not restored, after process death they close back to their origin. A second source of Viewer sessions that bypasses `launchViewer` would skip the retention claim and the card-origin check ([0220](0220-screens-leave-as-one-opaque-unit.md)).

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/TheoriaViewerLaunch.kt`: `launchViewer`, `openFeedViewer`, `openViewerSession`, `openSinglePostViewer`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaHomeDestinations.kt`: `openSearchViewer`, `ForYouDestination`, `forYouWiring`, `openForYouPost`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: the Viewer composable and `ViewerDestination`, `viewerTagSearchActions`, `CreatorDestination` entry
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShell.kt`: `openCreatorProfile`, `toggleLikeAndSyncCodex`, `feedLikeAction`, `openOriginalPost`, `openExternalPost`, `openStatisticsPost`, `previewStatisticsPost`
