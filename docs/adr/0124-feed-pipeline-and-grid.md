# 0124. Feed filtering and grids carry results forward and read hot state without recomposing everything

Status: Accepted

## Context

Feeds refilter as lengths arrive, the Viewer covers them, and watch history, likes and callbacks change constantly.

## Decision

- `rememberCarriedFilter` keeps the previous list on screen while refiltering off the main thread; a plain append filters only the new page; filters must keep or drop each post on its own and are data classes of everything they read so equality detects change. `retainKey` keeps each step's last result while the Viewer covers the feed. The length filter makes the route resolve every length, not only visible ones.
- Related shelf posts are capped so filtering in-frame is cheap; page demand maps grid positions back through the projection so an inserted shelf does not end the feed early. Each grid item carries its own index, since ahead-composed cards can recompose with an old entry; shelf and post items use distinct content types.
- Hot state goes through stable holders: `LocalPostWatchPresentation` is a `State`, per-post `rememberPostWatchEntry` uses derived state, `LocalCodexPostIds` is a `State`, `FeedCardEnvironment` is kept while callbacks are unchanged and calls the latest resolver, `FeedMarks` compares by value, and `CollectRouteEffects` uses the latest callback so a buffered effect is never lost nor navigation repeated.
- The source mix sliders keep local state so they stay responsive while writes complete asynchronously.

## Consequences

Cheap feeds, but filters and environments must stay value-comparable.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/FilteredFeed.kt`: `rememberCarriedFilter`, `extendFiltered`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/PostWatchPresentation.kt`: `LocalPostWatchPresentation`, `rememberPostWatchEntry`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedView.kt`: `FeedView`, `FeedMarks`, `FeedPostFilter`, `rememberFeedView`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedPostCard.kt`: `FeedCardEnvironment`, `rememberFeedCardEnvironment`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/PostStaggeredGrid.kt`: `FeedGrid`, `TwoColumnProjectedPostStaggeredGrid`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/RelatedFeedPresentation.kt`: `rememberRelatedFeedPresentation`, `rememberFeedPageDemand`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/routes/RouteEffectCollector.kt`: `CollectRouteEffects`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/CodexMembership.kt`: `LocalCodexPostIds`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/settings/UnifiedSourceSettings.kt`: `UnifiedSourceSettings`
