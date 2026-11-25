# 0217. A trail tag step can be removed by re-running the steps after it, and a typed tag is a new step

Status: Accepted

## Context

A trail is a stack of Viewer entries, one per step, and each tag step's query is its tag plus the include groups of the level it was opened from (ADR 0180). A Creator or Similar level has no query, so tags after it carry nothing from before it. Removing a step in the middle therefore changes every later tag step's query, and those levels are already built with their own feeds and sessions. The owner asked to remove a step without losing the rest of the path, to save a step's tag, and to add a tag the chips did not suggest.

## Decision

- An open step menu or the add sheet holds the Viewer's chrome (`ViewerTrailForks.holdChrome`), so auto-hide cannot close it mid-choice.
- Holding a tag step opens the tag sheet's existing hold menu (`TagActionMenu`) with Remove from trail and the saved-tags toggle (`rememberSavedTagAction`, the same store and toggle as the tag sheet's Save). Holding a suggested tag chip offers the toggle alone. A hold never runs the tap. The first crumb and Creator or Similar steps cannot be removed.
- `removal` decides what Remove does: it goes back to the step before the removed one and replays the later tag steps in order, stopping before the first Creator or Similar step, because that step was opened from one post in the removed step's results. Removing the current step only goes back.
- The replay goes through the normal fork path, one step at a time: `planTrailReplay` stores the remaining tags in the level's saved state, the level opens the first one, with its own source, through `ViewerTrailForks` like a tapped chip (`ViewerTrailReplay`), and `takeTrailReplay` hands the rest to the new level. Each step therefore gets the shorter path's query, the parent's filters and the `stillOver` check. Any other fork opened from that level drops the plan, and a replayed step that finds no posts ends the path there.
- The "+" pill opens a sheet whose suggestions come from `SearchSuggestionCoordinator.sourceTagSuggestions`, the general-tag autocomplete Unified search uses, for the post's source. A picked or typed tag opens as a fork of the current level.

## Consequences

Re-running reloads each later step's first page, so removing an early step in a long path takes one load per step. Steps after a Creator or Similar step are lost by design. A step restored after process death keeps its tag in saved state, so its menu still works.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrail.kt`: `removal`, `CrumbTag`, `TrailRemoval`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailRow.kt`: `ViewerTrailCrumb`, `ViewerForkChip`, `ViewerTrailReplay`, `HoldChrome`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailForks.kt`: `holdChrome`, `chromeHolds`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/TrailTagSheet.kt`: `TrailTagSheet`
- `app/src/main/java/com/theoriacodex/app/ui/trails/ViewerTrailLevel.kt`: `planTrailReplay`, `takeTrailReplay`, `recordStep`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/TagForkChip.kt`: `TagActionMenu`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/SavedTagAction.kt`: `rememberSavedTagAction`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchSuggestionCoordinator.kt`: `sourceTagSuggestions`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `removing a middle step goes back before it and re-runs the later steps in order (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `the re-run ends before a creator or Similar step (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `holding a step opens its menu without going back to it, and Remove removes that step (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `an open step menu keeps the chrome from hiding until it closes (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `a tag picked from the add sheet opens as the next step of this level (ADR 0217)`
- `app/src/test/java/com/theoriacodex/app/ui/trails/TrailReplayTest.kt`: `opening the next replayed step hands the rest of the path to the new level (ADR 0217)`
- `app/src/test/java/com/theoriacodex/app/ui/trails/TrailReplayTest.kt`: `opening anything else from the level drops the planned replay (ADR 0217)`
