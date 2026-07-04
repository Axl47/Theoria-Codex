# Dynamic Tag Refresh, Seen-Tag Ingestion, and Gelbooru Count Caching

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan must be maintained according to `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`.

## Purpose / Big Picture

After this change, Search/Explore tag suggestions no longer stay mostly static from seed data. The app now auto-refreshes source trending tags in the background while showing cached suggestions immediately, ingests tags seen in search results for Pixiv and Gelbooru, and batches Gelbooru tag-count lookups so Viewer Info tag metadata renders faster on repeat visits. Users should see fresher autocomplete/trending behavior over time with fewer repeated tag-count network waits.

## Progress

- [x] (2026-02-26 14:01Z) Added optional source adapter count-lookup contract and implemented Gelbooru batched `names` tag-count fetch support.
- [x] (2026-02-26 14:09Z) Added Search coordinator batch tag-count lookup path, cache write-back, and Pixiv smart-add canonicalization from autocomplete suggestions.
- [x] (2026-02-26 14:17Z) Implemented automatic TTL-based trending refresh with cached-first rendering and integrated result-tag ingestion into suggestion store.
- [x] (2026-02-26 14:24Z) Updated Viewer to consume batched tag-count fetch API (single pass per post tag set) and refreshed Explore manual refresh behavior.
- [x] (2026-02-26 14:31Z) Updated local suggestion-store merge policy to prioritize new data, preserve stronger metadata, and cap per-source entry growth.
- [x] (2026-02-26 14:37Z) Added/updated unit tests for Pixiv smart-add, Gelbooru batch count caching, and Gelbooru adapter names-query count parsing.
- [x] (2026-02-26 14:43Z) Validation complete: `./gradlew :core-sources:test :app:testDebugUnitTest` and `./gradlew assembleDebug` passed.

## Surprises & Discoveries

- Observation: Viewer Info tag metadata previously fetched missing counts one-by-one in sequence, which amplifies latency for posts with many tags.
  Evidence: `ViewerScreen` loop called `fetchTagVideoCount` per tag in `LaunchedEffect`.

- Observation: Existing suggestion-store merge order preserved older seeded metadata over incoming values for duplicate keys.
  Evidence: `mergeByText(existing + incoming)` combined with `previous.type ?: incoming.type` and `previous.count ?: incoming.count`.

- Observation: Gelbooru DAPI supports exact multi-tag count retrieval using `s=tag&q=index&names=<space-delimited-tags>`.
  Evidence: Gelbooru API docs (`howto:api`) specify `names` query for tag list lookups.

## Decision Log

- Decision: Keep trending refresh TTL in coordinator runtime state (in-memory) rather than persisted settings.
  Rationale: Prevent schema churn for a behavior-only optimization while still reducing repeated fetches during active sessions.
  Date/Author: 2026-02-26 / Codex

- Decision: Use optional adapter capability (`TagCountLookupSourceAdapter`) for batched count retrieval.
  Rationale: Keeps Search coordinator decoupled from concrete source classes and allows incremental source support.
  Date/Author: 2026-02-26 / Codex

- Decision: Ingest search result tags as `seen` suggestions without synthetic counts.
  Rationale: Result payloads do not reliably include global per-tag post counts, but tag text still improves fallback/autocomplete quality.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented the full scoped behavior: cached-first + auto-refresh trending suggestions, Pixiv smart-add canonicalization from suggestions, search-result tag ingestion for Pixiv/Gelbooru, batched Gelbooru tag-count fetch with cache write-back, and Viewer batch count consumption. Suggestion-store merge semantics now prefer newer/higher-quality metadata and cap growth to keep local persistence bounded.

Validation outcome:

- `./gradlew :core-sources:test :app:testDebugUnitTest` passed.
- `./gradlew assembleDebug` passed.

## Context and Orientation

Search state and suggestion behavior are coordinated in `app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`. Local suggestion persistence is in `app/src/main/java/com/theoriacodex/app/search/TagSuggestionStore.kt`. Viewer tag metadata fetch/render logic is in `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt` and wiring from app shell is in `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`.

Source adapters live in `core-sources`; Gelbooru implementation is `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`. Source contracts are in `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`.

## Plan of Work

First, extend source contracts with an optional batched tag-count capability and implement it for Gelbooru via DAPI names lookup. Then, update Search coordinator to use this capability for multi-tag fetches, persist fetched counts to local suggestion store, and expose a batch API for Viewer. In parallel, improve coordinator suggestion freshness by adding TTL-based auto refresh for trending tags, while preserving cached-first UX. After that, ingest seen tags from search result payloads to continually enrich fallback/autocomplete quality. Finally, update local store merge behavior to prioritize fresh metadata and cap growth, then adjust UI wiring/tests/docs.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Edit adapter contract and Gelbooru implementation:
   `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`
   `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`

2. Edit coordinator/store/UI wiring:
   `app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`
   `app/src/main/java/com/theoriacodex/app/search/TagSuggestionStore.kt`
   `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`
   `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`
   `app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt`

3. Add/update tests:
   `app/src/test/java/com/theoriacodex/app/search/SearchCoordinatorTest.kt`
   `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`

4. Update docs:
   `README.md`

5. Run validation commands:
   `./gradlew :core-sources:test :app:testDebugUnitTest`
   `./gradlew assembleDebug`

## Validation and Acceptance

Acceptance is met when all of the following hold:

- Pixiv source mode typed Add/Enter resolves to autocomplete canonical suggestion text when a suggestion matches normalized input (smart add), while still allowing non-suggested text.
- Search/Explore trending tags render immediately from cache and auto-refresh after TTL expiry without requiring manual refresh.
- Search result loads ingest Pixiv and Gelbooru seen tags into suggestion store, improving subsequent autocomplete fallback.
- Viewer Info tag metadata path performs one batch count fetch pass for missing Gelbooru tag counts (instead of per-tag sequential requests), and follow-up lookups reuse cached counts.
- Updated unit tests pass and no regressions appear in existing module tests.

## Idempotence and Recovery

All changes are additive and local to search/viewer/source contracts. Re-running validation commands is safe. If count lookup fails at runtime, coordinator falls back to existing autocomplete-based count resolution, preserving pre-change behavior. If auto-refresh fetch fails, cached trending tags remain visible and usable.

## Artifacts and Notes

Implementation stores tag-count lookups in the same suggestion store using `type=tag_count_lookup`, and seen search-result tags as `type=seen` entries. Store merge now keeps stronger metadata and higher known counts when duplicates appear.

## Interfaces and Dependencies

`core-domain` now exposes optional capability:

`TagCountLookupSourceAdapter.fetchTagCounts(tags: List<String>): Map<String, Int>`

Search coordinator consumes this capability via safe cast and preserves fallback behavior for sources that do not implement it.

Revision Note (2026-02-26): Initial creation during implementation of dynamic suggestion refresh and tag-count cache acceleration.
Revision Note (2026-02-26): Updated progress/outcomes with successful test and assemble validation results.
