# 0011. Unified search publishes early, merges stably and shapes requests per source

Status: Accepted

## Context

Unified search queries many sources with different term limits, slow or dead hosts, and overlapping mirrors. Provider parsing is heavy, and the user's screen must not shift as slower sources arrive.

## Decision

- Provider calls run on `Dispatchers.Default` (they parse whole pages) so the main thread keeps drawing; tests pass `EmptyCoroutineContext` to stay on the test dispatcher.
- `publishAsSourcesAnswer` publishes the first answers, then appends later sources below, so cards on screen never move; sources within `FIRST_ANSWERS_SETTLE_MS` of the first success interleave as a complete search would.
- `annotateListing` (such as `pictureHash`) runs on each page before merging, preserves order, and sits outside the source timeout so a page that arrived in time stays answered.
- A term-capped source gets only the leading terms (`withinTermLimit`): required groups before exclusions, in the order added. Ranked sorts spend one term on the provider's own order metatag (`SearchTermLimit.rankedSortUsesTerm`). For sources without grouped search, the term budget counts one term per "or" group, as if each request carried one alternative of it.
- A source without server-side grouping gets one request per alternative of the smallest "or" group (at most `MAX_FALLBACK_BRANCHES`, else the search is rejected). Each request carries that one alternative plus every single-term group; other "or" groups are not sent. Branch results are matched against the original groups locally, merged, and continue under one combined page token. Sources that group server-side get the query unchanged.
- Media terms go to the provider only (`withMediaTerms`); posts are still matched against the user's own terms, and the draft, history and statistics keep the query as written. Only a group that requires the animated term counts as already asking for it.
- Merged results go through `distinctByFile`; the earlier interleaved source keeps a mirrored file. Posts outside the date range are dropped before merging, since an out-of-range copy would hide the in-range copy on another site.
- A post whose detail page cannot be parsed is dropped during metadata hydration so unverified tags never reach local predicates, as is a post the source reports gone; source-wide failures or all posts failing still fail the page. Overlapping branches hydrate each original post id once, and dropping or replacing posts leaves each page's provider continuation token untouched.
- `SourceWeightNormalization`: explicit zero stays zero, missing entries get `missingWeight`, negative and non-finite values become zero, positives are scaled to total 1 by the largest value first (avoiding an infinite sum), and an all-zero input falls back to equal shares so the merge scheduler is never starved.

## Consequences

A slow source never blocks results, but its results always land below. Changing the weight contract affects every consumer of the normalized total.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt`: `providerContext`, `MAX_FALLBACK_BRANCHES`, `annotateListing`, `publishAsSourcesAnswer`, `searchSource`, `inDateRange`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/SearchMetadataHydration.kt`: `withSearchMetadata`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/SourceWeightNormalization.kt`: `SourceWeightNormalization`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/query/MediaTerms.kt`: `withMediaTerms`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/query/SearchTermLimit.kt`: `SearchTermLimit`, `requestTermCount`, `limitedToTerms`, `withinTermLimit`
