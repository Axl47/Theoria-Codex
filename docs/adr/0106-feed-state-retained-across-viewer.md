# 0106. Feed state outlives the Viewer, and feed work runs off the main thread without flashing or reshuffling

Status: Accepted

## Context

The Viewer is its own navigation destination, so a feed leaves composition each time a post opens and plain `remember` forgets everything. Whole-list work (filtering, sorting) over long feeds must not run on the main thread, and a feed grid that rebuilds its columns by guessing reshuffles cards.

## Decision

- `FeedRetainedState` (a ViewModel on the navigation entry) keeps the duration filter projection, computed frame caches and grid state while the Viewer covers the feed. `rememberRetainedFeedGridState` keeps column history; saved state covers process death, where only the scroll position can return. `ForgetRetainedFeedsEffect` drops feeds keyed under a prefix that can never show again, so they do not hold their posts until the screen leaves.
- `DurationFilterProjection` keeps the reveal order across the Viewer so a late post stays where it landed, and its last frame lets a returning feed start from it. A frame stays valid while posts only append; a replacement waits with resolving feedback. `stablePresented` keeps the previous presented list when nothing in it changed and starts a new `presentedDurationsKey` only when a post that stayed in place changed length, so downstream filters skip changes that alter nothing shown. An arrival that moves a post to the end counts as removed and appended.
- `MediaDurationStates` decisions belong to the post version the route synchronized: a post with the same id but other media (a related shelf's copy) gets none, since its own media was never looked up.
- `rememberComputedOffMain` computes a new list in place in the same frame (so paging tiles, scroll restoration and staggered lanes see list and result together), optionally deriving it from the previous frame via `extend` when only items were appended. A change to inputs alone recomputes on `LocalFeedComputationContext` (always `Dispatchers.Default` in the app) while the previous result stays on screen, so the list never flashes empty. Every frame holds a value computed from exactly its own list and inputs, so a discarded composition cannot corrupt the cache, and a late answer for an older list is dropped. With a `retainKey`, the first composition back reuses the retained frame only when the list is the same object and inputs are equal. `compute` and `extend` may read only `list` and `inputs`.

## Consequences

Returning from the Viewer shows the feed exactly as left. Retained state is only released through `forgetExcept` or when the navigation entry goes.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedRetainedState.kt`: `FeedRetainedState`, `rememberRetainedFeedGridState`, `ForgetRetainedFeedsEffect`, `rememberRetainedDurationProjection`
- `media-kit/src/main/kotlin/com/theoriacodex/app/ui/components/OffMainComputation.kt`: `LocalFeedComputationContext`, `rememberComputedOffMain`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/DurationFilterProjection.kt`: `DurationFilterProjection`, `stablePresented`, `rememberDurationFilterMetadata`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationPostMetadata.kt`: `DurationFilterMetadata`, `MediaDurationStates`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/ui/components/FeedRetainedStateTest.kt`: `a feed's grid state is the same object when the feed comes back into composition (ADR 0106)`, `forgetting keeps the kept feeds and their parts in every retained map, and leaves keys outside the prefix alone (ADR 0106)`, `a feed dropped from the kept set is forgotten while the effect stays in composition (ADR 0106)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/DurationFilterProjectionTest.kt`: `decisions published for one snapshot do not answer a replacement of the same post (ADR 0106)`, `a length that changes nothing shown keeps the presented list, and only an in-place length change renews its key (ADR 0106)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/ui/components/OffMainComputationTest.kt`: `a replaced list is computed in the same composition (ADR 0106)`, `an append with unchanged inputs extends only the appended items (ADR 0106)`, `an input change keeps the previous value until the off-main result lands (ADR 0106)`, `a list replaced during an off-main recompute keeps its in-place result (ADR 0106)`, `an input changed and changed back before the recompute lands publishes nothing stale (ADR 0106)`, `a returning feed reuses its retained frame only for the same list and inputs (ADR 0106)`
- `media-kit/src/main/kotlin/com/theoriacodex/app/ui/components/OffMainComputation.kt`: `val LocalFeedComputationContext = staticCompositionLocalOf<CoroutineContext> { Dispatchers.Default }`
- Left to prose: nothing stops production code from providing another `LocalFeedComputationContext` (only tests should); that `compute` and `extend` read only `list` and `inputs` is a rule for each caller's lambda; process-death restoration of the grid and the `ViewModel` living exactly as long as the navigation entry need a real activity and navigation host, so they are not guarded; the reveal order itself is guarded with `DurationReveal` in `app-logic`; that an appended frame stays valid while a replacement shows resolving feedback, and that a post moved to the end counts as removed and appended, have no test.
