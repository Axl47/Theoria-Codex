# 0080. A search is identified by query and source scope, and cached first pages are filtered to enabled sources

Status: Accepted

## Context
Results, cached first pages and saved scroll positions are stored per search, but the cache key does not include which sources are enabled. Unified search only sends terms every source understands.

## Decision
- `searchExecutionKey` identifies a query run in a source scope after dropping what the scope's mode cannot run; a temporary source pick is its own search.
- `cachedFirstPage` posts from outside the given sources are dropped because the key does not cover the enabled sources.
- Term-limit notices count only portable terms for Unified (`portableTermsForUnified`), so source-owned terms never count against a limit there.
- Partial and empty successes count as completed; failed and capability-excluded attempts do not (`completedSuccessfully`). An answer is admitted only when it carries the in-flight request's id and key (`admits`). Early results go on screen unless a cached first page is already there.
- When media is ANIMATED, the phone also hides still posts as a backup for sources that cannot ask for animated posts; nhentai alone is exempt since its galleries are never animated (`hidesStillsOnPhone`).

## Consequences
A stale answer for another search can never land on screen.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchExecutionContract.kt`: `searchExecutionKey`, `cachedFirstPage`, `completedSuccessfully`, `admits`, `showsPartialResults`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinator.kt`: `cachedFirstPage`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchTermLimitNotice.kt`: `searchTermLimitNotices`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchVisibilityFilters.kt`: `hidesStillsOnPhone`

## Guarded by

- `feed-kit/src/test/kotlin/com/theoriacodex/app/search/SearchCoordinatorTest.kt`: `temporary execution key binds canonical source selection (ADR 0080)`, `a cached first page returns only posts from enabled sources (ADR 0080)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchTermLimitNoticeTest.kt`: `queries within the budget and terms Unified never sends produce no notice (ADR 0079, 0080)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchExecutionContractTest.kt`: `a search key ignores terms the scope's mode cannot run (ADR 0080)`, `an answer counts as completed only when it succeeded and a source answered, even with no posts (ADR 0080)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchRequestReducerTest.kt`: `an answer for another search cancels its request and shows nothing (ADR 0080)`, `an answer is admitted only with the running request's id and key (ADR 0080)`, `early results show while the request keeps running (ADR 0080)`, `a cached first page holds back early results and is not yet the mode's search (ADR 0080, 0082)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchVisibilityFiltersTest.kt`: `animated-only hides stills on the phone in every mode but an nhentai search (ADR 0008, 0080)`
