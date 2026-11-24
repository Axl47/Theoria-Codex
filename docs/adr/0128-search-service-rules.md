# 0128. Search services own provider access only and keep bookkeeping ordered, capped and off the request path

Status: Accepted

## Context

Search results must appear without waiting for statistics, autocomplete must answer locally first, and providers differ in term limits and tag spelling.

## Decision

- `SearchCoordinator` owns provider access, durable writes and bounded caches; it never owns route jobs, queries, results, statuses or UI state. Each provider call returns an immutable value the view model admits by request identity. Posts are resolved through `PostResolutionFlights` shared with the Viewer, so overlapping requests for one post send a single provider request; a request that starts after another finished goes to the provider again, and failed-media recovery, which needs fresh media, makes its own request instead of coming through `PostResolutionFlights`.
- Every page passes `listed`, so tags and file copies are learned once per page. Every route into a single-source search passes one place that enforces the source's term cap.
- A thrown failure is the one source's failure; Unified reports per-source failures as statuses, so only a Pixiv search throws Pixiv's own error. A source removed since the last page drops its token so paging ends. A single-source retry gives up after `SINGLE_SOURCE_RETRY_TIMEOUT_MS`.
- `SeenTagRecorder` queues pages in call order (undispatched start on a fair mutex), outlives the request, and swallows bookkeeping failures so they cannot crash the scope.
- Sources that match only exactly spelled tags get their tags spelled as the source lists them (`prepareExactTags`, `UnifiedQueryPreparation`, reusing successful lookups).
- Autocomplete: cached answers are local only so the route publishes before its debounce; cached and fetched answers resolve through the same steps; Unified offers only portable general tags; a provider answer is not refetched while a recent one is fresh; searched tags are capped at `SEARCHED_TAG_SUGGESTION_LIMIT`.
- Tag counts are matched per source, and typed counts live apart from the plain-tag store so equal names cannot cross namespaces. `TagCountCoordinator` uses an explicit provider capability, and unavailable decisions stop repeated requests.

## Consequences

Search tests inject schedulers; changing recorder ordering changes statistics order.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinator.kt`: `SearchCoordinator`, `listed`, `prepareExactTags`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/PostResolutionFlights.kt`: `PostResolutionFlights`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SeenTagRecorder.kt`: `SeenTagRecorder`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchSuggestionCoordinator.kt`: `SearchSuggestionCoordinator`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchTermCountCoordinator.kt`: `SearchTermCountCoordinator`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/TagCountCoordinator.kt`: `TagCountCoordinator`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinatorPolicy.kt`: `SINGLE_SOURCE_RETRY_TIMEOUT_MS`, `SEARCHED_TAG_SUGGESTION_LIMIT`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/UnifiedQueryPreparation.kt`: `UnifiedQueryPreparation`
