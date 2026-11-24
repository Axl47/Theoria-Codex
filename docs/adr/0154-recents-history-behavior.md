# 0154. History removals keep their place for Undo, its related shelf never joins History, and swipes are left-only

Status: Accepted

## Context

History (Recents) mixes searches and viewed posts in grids, rows and a per-day timeline. Entries can be removed by swipe or TalkBack, and a related shelf can be opened inside it. Layout stability and Viewer return depend on how these are handled.

## Decision

- `RecentsEntryRemoval` removes an entry at once and offers Undo that restores the exact entry (time, origin and progress included). A removed search stays in the list as `removedSearch` so its row draws the offer in place; a removed post tile offers Undo in History's pill, since the grid closes the gap. Offers and restores run in a scope that outlives History, and it is a ViewModel so the placeholder survives recreation.
- The All view's rows scroll sideways, so their entries are removed from the post menu or by TalkBack rather than by a swipe (`historyRemoval`); a removed search keeps its tile's place.
- `SwipeToRemove` claims only a leftward horizontal drag, so rightward and vertical drags pass through and the tab pager and list keep working.
- The All view groups newest-first activity by day: searches as one row above one shelf of posts. How searches and views interleaved within a day is deliberately not shown. Date labels are built per call so a locale change takes effect.
- A day's post row shows the newest post whenever it changes, but not on a return from another Library section that finds the same newest post (rows are newest first and a list keeps its place by key).
- Headings and the shelf hold a null in the post list so a Viewer return still finds each post by its grid position.
- The related shelf in Recents (`RecentsRelatedViewModel`) closes when the tab or source changes; Recents posts come from history, so shelf posts are never promoted into it.
- Saving a search from History (`RecentSearchSaving.bookmark`) never saves twice: an already saved search opens its rename instead. `SavedSearchViewModel` owns durable pin changes outside the lifetime of the picker or name dialog.

## Consequences

Undo always finds its row or tile where it was. Adding a History view needs a removal path that does not depend on a swipe where the view scrolls sideways.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/RecentsEntryRemoval.kt`: `RecentsEntryRemoval`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/RecentsLists.kt`: `ActivityList`, `historyRemoval`, `RecentPostsRow`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/SwipeToRemove.kt`: `SwipeToRemove`, `awaitLeftSwipeStart`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/RecentsTimeline.kt`: `recentsTimeline`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/RecentsRelated.kt`: `RecentsRelatedViewModel`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/RecentSearchSaving.kt`: `RecentSearchSaving`
- `feature-library/src/main/kotlin/com/theoriacodex/app/recents/SavedSearchViewModel.kt`: `SavedSearchViewModel`
