# 0162. Series listings and filters tell sources apart by language only when needed, keep loaded pages on failure, and virtualise filter groups

Status: Accepted

## Context

One extension is often several sources, one per language, all with the extension's name. Sources fail often, filter trees can hold hundreds of tags, and the filter sheet is used with TalkBack.

## Decision

- `SeriesSource.label` adds the language after the name only when another source on has the same name; source pickers and search sections show language only when the listed sources do not all share one.
- A source failure shows a line for its reason (`failureLine`), never an exception's own text, except `UnsupportedEntriesException`, whose text is written for the user. A failed next page keeps the loaded series on screen and holds that page back until Retry (`withPage`). `firstPage` also serves the Switch source sheet's search.
- `SourceLoadFailure` shows Retry, or while the source needs browser verification a line saying so with the action that opens the app's browser check; once that need clears, here or anywhere else, the load runs again by itself.
- `filterItems` gives each filter, and each member of an open group, its own lazy item keyed by its place in the tree, so a group of hundreds of tags composes only what shows.
- Reset in the filter sheet is disabled until the filters have loaded: a reset before that would be undone when they arrive.
- A three-state filter row sets its own `stateDescription` (Ignored, Included, Excluded) because TalkBack would call the excluded dash "partially checked".
- A newly chosen sort order starts descending; choosing it again flips the direction.
- A chapter row's menu is composed only while open, since a long chapter list would otherwise hold a closed menu per row.

## Consequences

New filter types must be drawn through `filterItems` and expose their own accessible state when the system's default would mislead.

## Applies to

- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SourceLabel.kt`: `label`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabViewModel.kt`: `failureLine`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/PagedListing.kt`: `PagedListing`, `firstPage`, `withPage`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SourceLoadFailure.kt`: `SourceLoadFailure`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesFilterSheet.kt`: `SeriesFilterSheet`, `filterItems`, `TriStateRow`, `SortRows`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesBrowseView.kt`: `SourcePickerSheet`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabScreen.kt`: `searchResults`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesPageChapters.kt`: chapter row menu
