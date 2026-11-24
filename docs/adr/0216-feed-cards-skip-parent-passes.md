# 0216. Feed cards skip parent passes and measure visibility against their feed

Status: Accepted

## Context

Feed screens recompose often: every results page, page request, duration update, like and Viewer feed change. Kotlin's strong skipping compares a composable's unstable parameters, and the values a lambda captures, by identity. A card lambda that captured a support, config or callbacks object built fresh on each pass, or the whole `SearchUiState` or Codex selection, was a new lambda every time, so every visible card ran again. Counted with a composition tracer on host tests, a Search page request re-ran all 4 visible cards and its page all 4 plus the 2 it added, a For You like all 6, a Search like all 4, and one Codex checkmark all 4 cells.

Likes and acquired durations reach cards through `State`s that each card reads through `derivedStateOf`. `rememberUpdatedState` writes that `State` during composition, and Compose invalidates every derived-state reader of a value written during composition without checking whether the derived result changed, so one like re-ran every card.

Each card also tracked its own visibility with `onGloballyPositioned` and `boundsInWindow(clipBounds = true)` on every frame its position changed, which is every scroll frame for every visible card, and collected the lifecycle state itself.

## Decision

Values a grid cell receives are identity-stable across parent passes. Routes remember the objects they hand down (`TriageCardSupport`, `ForYouScreenConfig`, `ForYouDeckCallbacks`) with explicit keys; cell lambdas capture the card environment rather than the config holding it, and read callbacks and changing collections through `rememberUpdatedState` instead of capturing them. A Codex cell receives `editing` and `selected` booleans, not the whole selection.

`rememberFeedCardEnvironment` and the related shelf publish card-shared states after composition (`SideEffect`), so Compose compares each card's derived value and recomposes only cards whose value changed. A like shows one frame after the marks change.

A card's media reports visibility through two `Modifier.onVisibilityChanged` checks: one against the enclosing feed viewport (the grid in `AdaptivePostGrid`, or the column in `ViewerReturnColumn`, provided as `FeedViewport` through `LocalFeedViewport`; the window outside one) and one against the window. Media counts as visible while some part of it lies inside both, so every card the user can see still autoplays, media scrolled under a header while its caption shows does not, and a feed the pager has moved beside the window during a tab swipe plays nothing. Duration visibility is reported from an effect keyed on the post and its visibility, so a slot reused for another post reports both changes. The feed viewport also supplies the lifecycle state cards read. The card keeps its media coordinates from `onPlaced` and computes bounds only on a click, and tracks positions for the Viewer return only on the card a pending return names.

Guarded by `SearchCardRecompositionTest`, `ForYouDeckScreenTest`, `CodexEditModeTest`, `FeedGridVisibilityTest`, `FeedMediaVisibilityTest`, `SearchCardResolutionTest` and `ViewerSavedReturnTest`.

## Consequences

A new feed screen that builds card parameters inline will pass review but re-run every card on each pass; copy the remembered pattern and add a case to the recomposition tests, which count card bodies with `countCompositions`.

A scrolling container of cards that is neither `AdaptivePostGrid` nor `ViewerReturnColumn` must wrap its content in `ProvideFeedViewport`, or media its own clip hides but the window still contains counts as visible. Clips other than the feed viewport and the window are not consulted: on a wide layout, mid-swipe, a card passing under the navigation rail still counts as visible. Consulting every ancestor clip is the per-frame bounds walk this replaced.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/routes/ForYouRoute.kt`: `ForYouRoute`, `rememberDeckCallbacks`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckBody.kt`: `ForYouGrid`, `ForYouDeck`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScreen.kt`: `SearchScreen`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailScreen.kt`: `CodexDetailBody`, `CodexSelectablePostCard`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedPostCard.kt`: `rememberFeedCardEnvironment`, `rememberStateSetAfterComposition`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedViewport.kt`: `FeedViewport`, `LocalFeedViewport`, `ProvideFeedViewport`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/PostStaggeredGrid.kt`: `AdaptivePostGrid`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnContract.kt`: `ViewerReturnColumn`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchResultCard.kt`: `SearchResultCard`, `SearchCardMediaGeometry`
- `feed-kit/src/testFixtures/kotlin/com/theoriacodex/app/testing/CompositionCounter.kt`: `countCompositions`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchCardRecompositionTest.kt`: `SearchCardRecompositionTest`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/ui/components/FeedGridVisibilityTest.kt`: `FeedGridVisibilityTest`
