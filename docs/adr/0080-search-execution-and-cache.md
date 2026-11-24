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
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchTermLimitNotice.kt`: `searchTermLimitNotices`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchVisibilityFilters.kt`: `hidesStillsOnPhone`
