# 0091. Feed chrome avoids layout jumps, per-frame recomposition and noisy announcements

Status: Accepted

## Context

Feeds are staggered grids with overlay controls, scrolling every frame, and media that lands after layout. Careless chrome makes cards change column, recomposes whole feeds, or spams accessibility services.

## Decision

- `CollapsingSearchChrome` saves the measured toolbar height so the first frame back from a pushed screen reserves it instead of sliding results down while media lands. The reserved height includes the toolbar gap so removing the spacer cannot snap the grid upward.
- `FeedScrollSignal`: scroll events arrive every frame, so each only bumps a counter and one job watches for a quiet window. Only `FeedFilterFab` reads the state, so scrolling does not recompose the feed.
- `feedFilterSemantics` marks the Undo state as a polite live region, because it replaces the clear button that had focus.
- `VisibleItemIndices` reallocates the visible index list only when it changes, not on every scrolled frame.
- `FeedLaneJumpLogger` (debug only) keeps its history outside the feed's composition, so a card that changes column across the Viewer is still caught. It logs under `TheoriaGridJump`.
- `InlineNameField`: a focus loss commits on the next frame from the field's own scope. Tearing the field down (rotation, leaving the screen) also takes focus but cancels that scope first, so it renames nothing. `InlineNameFieldState` makes Done followed by the focus loss it causes commit once.
- Feed trace sections (`FeedTraceSections`) are opened by composition-side callers, not inside the pure filter and sort functions, which plain JVM tests call without an Android `Trace`.
- `LikeFeedback.tapped` is called from the tap, not from state, so scrolling or swiping onto an already liked post stays still. `likePop` applies the scale in the draw phase so the animation never recomposes its card.

## Consequences

New feed chrome should read volatile state in the draw or layout phase and persist measured sizes across navigation. Moving trace calls into the pure functions would break JVM tests.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/CollapsingSearchChrome.kt`: `CollapsingSearchChrome`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedFilters.kt`: `FeedScrollSignal`, `feedFilterSemantics`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedPageDemandEffect.kt`: `VisibleItemIndices`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedLaneJumpLogger.kt`: `FeedLaneJumpLogger`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/InlineNameField.kt`: `InlineNameField`, `InlineNameFieldState`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedTraceSections.kt`: `FeedTraceSections`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/motion/LikeFeedback.kt`: `LikeFeedback`, `likePop`
