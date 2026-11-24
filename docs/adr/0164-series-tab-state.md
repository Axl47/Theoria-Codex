# 0164. The Series tab keeps some state across process death, holds the grid until saved options arrive, and drops stale answers

Status: Accepted

## Context

Source calls are slow, sources and extensions can change while answers are in flight, and the system can kill the process while the tab is open. The tab must not show the Following grid under default options and then reorder it, and must not ask a source for a listing the screen hides.

## Decision

- Across process death only the search text, the browsed source and the view are kept (`SAVED_SEARCH`, `SAVED_BROWSE`, `SAVED_VIEW`); the browsed listing, its filters, the dedicated search, the filter sheet, the kind filter and the view's other state restart at the source's popular series. Search and browsed source show again once sources have loaded. The Following grid's sort and filter persist across launches through `SeriesLibraryPreferences`, whose preferences open lazily on the first read, off the main thread; a write comes after it.
- The saved view is restored at once. When there is none, the default (Following when any series are followed, else Browse) is chosen only after the saved options are read, and a view chosen with `chooseView` during that read wins. `content` passes no followed series until `libraryLoaded`, so the Following grid never shows under the defaults first.
- Browse loads a listing only while it shows with nothing browsed, no search on screen and sources loaded (`browseWhenShown`); it takes the source browsed before process death, else the first. `searchAllSources` starts the search before switching to Browse for the same reason.
- A source that is replaced (its extension updated) is browsed afresh when Browse shows, else dropped until its listing shows. A search on screen, or one sent before the sources loaded, runs in every source that is new or replaced. Labels are taken again on each change because a source turned on can share a name with one already listed. An answer from a source switched off or replaced meanwhile is dropped.
- `searchRun` and `dedicatedRun` count submitted searches and listings; an answer asked for under an earlier run is dropped, even when its text is the same. `followShown` is followed in the view model, not in the tab's composition, because the pager drops the composition before it reports another tab settled; once the tab is left the filter sheet closes and a filtered browse returns to popular.
- The filter sheet's filters load afresh each time it opens, since some sources fill theirs in from what they fetched meanwhile; a source whose filters fail to load offers none, and the sheet still searches it. Searching before the filters load keeps the filters of the listing on screen and changes only the text.
- The tab's query is hoisted above the dedicated-source switch so returning from a source's search keeps the query and scroll positions.
- `arranged` keeps the stored order (unread first, most first) for ties and for series a time sort has no time for, which go last.

## Consequences

Anything new that must survive process death goes through the saved-state handle; everything else is expected to restart. A listing must be started through `browse`, `choose` or `search` so run counters stay in step.

## Applies to

- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabViewModel.kt`: `content`, `browseWhenShown`, `showSources`, `searchIn`, `updateSearch`, `followShown`, `openFilters`, `searchSource`, `searchAllSources`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabScreen.kt`: `SeriesTabScreen` query state
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesLibrary.kt`: `SharedPreferencesSeriesLibraryPreferences`, `arranged`
