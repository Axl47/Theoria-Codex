# 0084. Date and length filters run on the device with one animated-media rule for every source

Status: Accepted

## Context
Not every source supports date or animated filtering on the server, and previews can be stills even for animated posts.

## Decision
- The date range is applied on the device so every source can filter by date, including those without server support. Dates are read in UTC to match both the picker and the date sent to the source; picker dates mark the start of a UTC day so the whole end day counts. Preset ranges are fixed when chosen, so one that no longer ends now reads as the custom dates it has become (`presetFor`).
- One rule for every source decides "animated": animated media, or a tag that says so, which covers sites whose previews are stills (`matchesAnimatedFilter`).
- A length filter shows only animated posts whose length is known and in range; unknown length stays hidden.
- `markInputs` narrows liked, saved and watched marks to what the active hide toggles read, so changing a mark does not refilter the feed while its toggle is off.

## Consequences
Filter behavior is identical across sources.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchQueryOptions.kt`: `presetFor`, `customDateRange`, `dateRangeLabel`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchVisibilityFilters.kt`: `SearchVisibilityFilters`, `PostMarkInputs`, `markInputs`, `matchesAnimatedFilter`, `matchesAnimatedDurationFilter`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchDateRangeTest.kt`: `each preset round trips while it still ends now (ADR 0084)`, `a preset chosen days ago reads as custom (ADR 0084)`, `custom range covers its whole last day and labels in UTC (ADR 0084)`, `local date filter keeps posts in range and hides unknown dates (ADR 0084)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchVisibilityFiltersTest.kt`: `a still post tagged as animated passes the animated filter and an untagged one does not (ADR 0084)`, `filterSearchResults excludes static posts from an active duration range (ADR 0084)`, `filterSearchResults hides animated posts whose length is unknown (ADR 0084)`, `filterSearchResults uses separate acquired metadata without rewriting posts (ADR 0084)`, `mark inputs drop marks whose hide toggle is off so new likes keep the same key (ADR 0084)`
