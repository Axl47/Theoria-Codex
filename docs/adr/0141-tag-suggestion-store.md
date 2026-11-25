# 0141. The tag suggestion store seeds from a bundled asset, bounds its size, and survives damaged files and failed writes

Status: Accepted

## Context

Search autocomplete reads tags and counts from a local store that is filled by providers, seeded from a bundled asset, and persisted to `tag_suggestions.json`. Compose reads counts while merges run, files written by older builds (Gson) must keep loading, and a damaged file or entry must not cost the user the whole cache.

## Decision

- `loadSeedTagSuggestions` reads the bundled `tag_store.json`; an unreadable asset yields no suggestions rather than failing. `parseSeedTagSuggestions` decodes each source list and each entry on its own, so a malformed list or entry skips only itself.
- The persisted snapshot (`TagStoreSnapshot`) uses nullable lists so a damaged source list or entry does not discard the whole cache.
- `FileBackedTagSuggestionStore` charges every entry's approximate persisted size (`estimatedBytes`, never persisted, recomputed by `copy`) against a total byte budget. After seeding, the estimate is rebuilt from scratch (`rebuildByteEstimateLocked`) so a seed past the budget gets trimmed.
- Counts are read from an immutable index (`countsBySource`, `@Volatile`) published under the store lock, so Compose-driven `getCount` reads never wait on merges. Trimmed sources republish their index, because otherwise `getCount` would keep answering for tags the store dropped.
- Each source's trending refresh time is persisted with the trending tags (`trendingRefreshedAt`). `SearchSuggestionCoordinator` and the For You lanes stamp it when a fetch starts (`markTrendingRefreshed`), whatever the fetch returns, so the `TRENDING_REFRESH_INTERVAL_MS` window holds across process restarts and a failed or rate-limited fetch is not repeated on every cold start; an empty cache still forces a fetch.
- A persistence failure keeps the dirty version pending so `flush` or the next mutation can retry it; cancellation still propagates.

## Consequences

Autocomplete keeps working with a missing asset, a half-damaged cache or a failing disk, at the cost of a stale on-disk copy until a later write succeeds. Changing the persisted schema must keep loading the Gson-era and sparse files pinned by the goldens.

## Applies to

- `feature-search/src/main/kotlin/com/theoriacodex/app/search/TagSuggestionSeedData.kt`: `loadSeedTagSuggestions`, `parseSeedTagSuggestions`, `TagSeedFile`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/TagSuggestionStore.kt`: `FileBackedTagSuggestionStore`, `countsBySource`, `schedulePersistenceLocked`, `rebuildByteEstimateLocked`, `TagStoreSnapshot`, `CachedSuggestion.estimatedBytes`, `trendingRefreshedAt`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchSuggestionCoordinator.kt`: `shouldRefreshTrending`, `fetchTrendingForSource`
