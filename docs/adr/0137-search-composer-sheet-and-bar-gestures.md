# 0137. The composer is an undimmed bottom sheet that leaves results in view, and the collapsed bar steps through history by swipe

Status: Accepted

## Context

The composer edits the query while results update above it, with the keyboard open on short screens. A platform Dialog owns the keyboard and system Back, but dims what is behind it. The collapsed search bar sits inside the home pager, whose sideways swipe changes tab, so a bar swipe must not fight it.

## Decision

- `SearchComposerSheet` stays a `Dialog` so it owns the keyboard and system Back, but is laid out as an undimmed sheet along the bottom edge (`UndimmedDialogWindow` sets the dim amount to 0) so results stay readable and keep updating. A tap above the sheet closes it. `replacing` shows a slim progress line along its top edge while newer results are on the way.
- The sheet takes at most `SHEET_MAX_FRACTION` (0.62) of the window, and, when the window allows, leaves `RESULTS_PEEK` (200 dp, measured from the window's top, where the status bar and search bar take about half) above it, with the keyboard counted. `SHEET_MIN_HEIGHT` (360 dp) takes precedence: the sheet is never shorter than 360 dp (or the available height if smaller), so with the keyboard up or on a short window it can cover the results entirely.
- While suggestions share the screen with the query and the keyboard, the query is compact and capped at the smaller of `COMPACT_QUERY_MAX_HEIGHT` (144 dp) and `COMPACT_QUERY_MAX_FRACTION` (0.4) of the height, so suggestions always stay reachable. The query stays on screen while suggestions take the room it gives up.
- Saved tag and saved search chips are capped at `SAVED_CHIP_MAX_WIDTH` because a scrolling row offers unbounded width, which would leave the chip's label none.
- `searchBarHistorySwipe` lets the collapsed bar step through settled searches. Only a swipe toward an available step claims the gesture once it passes touch slop, so the home pager does not page while the bar steps; a swipe the other way stays the pager's and changes tab. TalkBack gets the same steps as custom actions (Previous search, Next search).

## Consequences

On normal windows the composer leaves results in view; on short or keyboard-constrained windows query space wins over visible results. A full-screen composer or a dimming scrim would drop the live-results behaviour.

## Applies to

- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposerSheet.kt`: `SearchComposerSheet`, `UndimmedDialogWindow`, `SHEET_MAX_FRACTION`, `RESULTS_PEEK`, `SHEET_MIN_HEIGHT`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchComposer.kt`: `COMPACT_QUERY_MAX_HEIGHT`, `COMPACT_QUERY_MAX_FRACTION`, `SAVED_CHIP_MAX_WIDTH`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchBarHistorySwipe.kt`: `searchBarHistorySwipe`, `SearchBarSwipe`, `SearchBarHistory`
