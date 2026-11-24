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

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestratorTest.kt`: `provider calls run off the caller's thread by default (ADR 0011)`, `a slow source never holds back results and only appends below them (ADR 0011)`, `sources answering together are interleaved without a partial publication (ADR 0011)`, `a page that arrived in time stays answered when annotation outlasts the source timeout (ADR 0011)`, `annotations reach the merge so a picture two sources list appears once (ADR 0011)`, `fallback branches one OR group filters canonically and retains branch continuation (ADR 0011)`, `a file two sources host appears once, from the source interleaved first (ADR 0011)`, `a date search keeps the copy of a file that is in range (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/query/SearchTermLimitTest.kt`: `ranked sorts spend one term on their order metatag (ADR 0011)`, `an or group costs one term because its alternatives are requested separately (ADR 0011)`, `limiting keeps required groups before exclusions (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/GroupedSearchPagingTest.kt`: `multiple required groups keep an entirely filtered page resumable without restarting exhausted branches (ADR 0011)`, `a source without grouped search accepts at most eight alternatives in a group (ADR 0011)`, `a source without grouped search branches on the smallest or group (ADR 0011)`, `a source that groups server-side gets the query unchanged (ADR 0011)`, `the animated term reaches the provider but not the local match or the reported query (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/query/MediaTermsTest.kt`: `nothing is added when unasked, already asked, or the source has no term (ADR 0011)`, `an animated alternative in an OR group still gets the term (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/SearchMetadataHydrationTest.kt`: `one unparseable detail page drops only that post and keeps the page (ADR 0011)`, `a source-wide failure on one detail page still fails the page (ADR 0011)`, `metadata failures remain failures instead of becoming empty successful results (ADR 0008, 0011)`, `group verification hydrates overlapping identities once and deduplicates after canonical resolution (ADR 0011)`, `a post the source reports gone is dropped even when its listing matches (ADR 0011)`, `hydration leaves the provider's continuation token as it was (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/SourceAnswerPublicationTest.kt`: `failures alone publish nothing and the first success publishes after the settle window (ADR 0011)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/SourceWeightNormalizationTest.kt`: `normalizes positive weights while preserving explicit zero (ADR 0011)`, `missing values use the declared fallback without replacing explicit zero (ADR 0011)`, `invalid weights fail closed and finite positives still total one (ADR 0011)`, `all zero input falls back to equal shares (ADR 0011)`
- Left to prose: keeping a page's order is the annotator's own contract, and the main thread staying free to draw is device behaviour.
