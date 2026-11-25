# 0217. A trail tag step can be removed by re-running the steps after it, and a typed tag is a new step

Status: Accepted

## Context

A trail is a stack of Viewer entries, one per step, and each tag step's query is its tag plus the include groups of the level it was opened from (ADR 0180). A Creator or Similar level has no query, so tags after it carry nothing from before it. Removing a step in the middle therefore changes every later tag step's query, and those levels are already built with their own feeds and sessions. The owner asked to remove a step without losing the rest of the path, to save a step's tag, and to add a tag the chips did not suggest.

## Decision

- An open step menu or the add sheet holds the Viewer's chrome (`ViewerTrailForks.holdChrome`), so auto-hide cannot close it mid-choice.
- Holding a tag step opens the tag sheet's existing hold menu (`TagActionMenu`) with Remove from trail and the saved-tags toggle (`rememberSavedTagAction`, the same store and toggle as the tag sheet's Save). Holding a suggested tag chip offers the toggle alone. A hold never runs the tap. The first crumb and Creator or Similar steps cannot be removed.
- `removal` decides what Remove does: it goes back to the step before the removed one and replays the later tag steps in order, stopping before the first Creator or Similar step, because that step was opened from one post in the removed step's results. Removing the current step only goes back.
- The replay goes through the normal fork path, one step at a time: `planTrailReplay` stores the remaining steps in the level's saved state, the level opens the first one, with its own source, through `ViewerTrailForks` like a tapped chip (`ViewerTrailReplay`), and `trailReplayAfter` hands the rest to the new level. Each step therefore gets the shorter path's query, the parent's filters and the `stillOver` check. The plan is cleared only when an open ends (`clearTrailReplay`): a swipe or a failed `stillOver` cancels the open, and the level replays again from the post now on screen. Any other fork opened from that level drops the plan, and a replayed step that finds no posts ends the path there.
- A level waiting to replay does not report its visible post (`onVisiblePostChanged`), so passing through it marks nothing watched and feeds no statistics. Returning to it later composes it again and reports as usual.
- The re-run stays on the navigation stack instead of loading every step and pushing the entries at once, because each Viewer entry claims its session from the single retained slot when it composes; pushing several entries would need per-entry session claiming across Viewer launch and restoration. A trail kept as data (backlog #458's bolder idea) would remove that limit.
- The "+" pill opens a sheet whose suggestions come from `SearchSuggestionCoordinator.sourceTagSuggestions`, the general-tag autocomplete Unified search uses, for the post's source. A picked or typed tag opens as a fork of the current level.
- The trail is the Viewer's level stack wherever the Viewer was opened (Search, Codex, For You and the rest), and it is active once it has a step beyond the first crumb. Holding a general tag in the info sheet adds `ViewerTrail.tagActions` before the Search moves: with no active trail, Start a trail, which is the tag's tap; otherwise Add to trail (the same fork) or, for a tag that matches a step after the first crumb as its source spells tags (`sourceTagsMatch`), Remove from trail with `removal`'s semantics, and Start a new trail. Start a new trail replaces the trail without asking, like tapping an earlier crumb: `ViewerLevel.restart` goes back to the first crumb and plans the tag as a one-step replay, so it opens through the same fork path as a step re-run.

## Consequences

Re-running reloads each later step's first page, so removing an early step in a long path takes one load per step, and each passed-through level briefly shows its first post. Steps after a Creator or Similar step are lost by design. A step restored after process death keeps its tag in saved state, so its menu still works.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrail.kt`: `removal`, `CrumbTag`, `TrailRemoval`, `tagActions`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailRow.kt`: `ViewerTrailCrumb`, `ViewerForkChip`, `ViewerTrailReplay`, `HoldChrome`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `ViewerLoadedScreen`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailForks.kt`: `holdChrome`, `chromeHolds`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/TrailTagSheet.kt`: `TrailTagSheet`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/PostTagActionSection.kt`: `PostTagSheetExtras.trailActions`
- `app/src/main/java/com/theoriacodex/app/ui/trails/ViewerTrailLevel.kt`: `restart`, `planTrailReplay`, `trailReplayAfter`, `clearTrailReplay`, `recordStep`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/TagForkChip.kt`: `TagActionMenu`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/SavedTagAction.kt`: `rememberSavedTagAction`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchSuggestionCoordinator.kt`: `sourceTagSuggestions`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `removing a middle step goes back before it and re-runs the later steps in order (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `a tag's trail moves are Start without a trail, then Add or Remove with Start a new trail (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `a step matches its tag as the source spells it, past a first crumb with the same tag (ADR 0217)`
- `app/src/test/java/com/theoriacodex/app/tags/TagForkChipTest.kt`: `holding a forking chip offers its trail moves (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTest.kt`: `the re-run ends before a creator or Similar step (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `holding a step opens its menu without going back to it, and Remove removes that step (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `an open step menu keeps the chrome from hiding until it closes (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerTrailTagActionsTest.kt`: `a tag picked from the add sheet opens as the next step of this level (ADR 0217)`
- `app/src/test/java/com/theoriacodex/app/ui/trails/TrailReplayTest.kt`: `the replayed step's level gets the rest of the path, and the plan holds until the open ends (ADR 0217)`
- `app/src/test/java/com/theoriacodex/app/ui/trails/TrailReplayTest.kt`: `opening anything else from the level drops the planned replay (ADR 0217)`
