# 0084. Date and length filters run on the device with one animated-media rule for every source

Status: Accepted

## Context
Not every source supports date or animated filtering on the server, and previews can be stills even for animated posts.

## Decision
- The date range is applied on the device so every source can filter by date, including those without server support; every fetched page is filtered, single-source and Unified alike (`UnifiedSearchOrchestrator.fetchSource`). Dates are read in UTC to match both the picker and the date sent to the source; picker dates mark the start of a UTC day so the whole end day counts. A preset is stored as its number of days (`DateRange.LastDays`) and covers the days before each run, open-ended so a post dated slightly ahead of the device clock still counts; picked dates stay fixed (`DateRange.Between`). Feed filters hold fixed bounds (`SearchVisibilityFilters.dateRange`), resolved once where a feed builds them, so a post already shown never drops out because time passed while browsing. Ranges stored before presets were relative stay the fixed dates they were saved with, even when their span matches a preset: recents and pins key their identity on a stored hash that holds those dates, so reading them as presets would split a search from its own history and saved pin.
- One rule for every source decides "animated": animated media, or a tag that says so, which covers sites whose previews are stills (`matchesAnimatedFilter`).
- A length filter shows only animated posts whose length is known and in range; unknown length stays hidden.
- `markInputs` narrows liked, saved and watched marks to what the active hide toggles read, so changing a mark does not refilter the feed while its toggle is off.

## Consequences
Filter behavior is identical across sources.

## Applies to
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt`: `DateRange`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchQueryOptions.kt`: `presetFor`, `customDateRange`, `dateRangeLabel`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/RecentsStorage.kt`: `QueryStorageCodec`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchVisibilityFilters.kt`: `SearchVisibilityFilters`, `PostMarkInputs`, `markInputs`, `matchesAnimatedFilter`, `matchesAnimatedDurationFilter`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchDateRangeTest.kt`: `a preset stays that preset and covers the days before each run (ADR 0084)`, `picked dates stay custom (ADR 0084)`, `custom range covers its whole last day and labels in UTC (ADR 0084)`, `local date filter keeps posts in range and hides unknown dates (ADR 0084)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/TrailingDateRangeTest.kt`: `a trailing date range is measured from each run, not from when it was chosen (ADR 0084)`, `a single-source search drops posts outside its date range too (ADR 0084)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/RecentSearchPayloadGoldenTest.kt`: `a preset is stored as its days and loads back relative, and stored dates stay fixed (ADR 0038, 0084)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchVisibilityFiltersTest.kt`: `a still post tagged as animated passes the animated filter and an untagged one does not (ADR 0084)`, `filterSearchResults excludes static posts from an active duration range (ADR 0084)`, `filterSearchResults hides animated posts whose length is unknown (ADR 0084)`, `filterSearchResults uses separate acquired metadata without rewriting posts (ADR 0084)`, `mark inputs drop marks whose hide toggle is off so new likes keep the same key (ADR 0084)`
