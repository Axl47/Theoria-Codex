# 0122. Drag-to-collect gestures act on the highlighted target at release and stay accessible

Status: Accepted

## Context

Lifting a card or swiping a triage card shows save, like and hide targets under a moving finger. Feeds update, targets load late, and TalkBack users cannot drag.

## Decision

- What the user sees highlighted is what a release acts on (`session.targetAt`); triage reads the hovered targets at release, before the lifted pointer clears them.
- Only a release commits. A cancelled drag springs back however far it went; a release that outlives its card is cancelled (`cancelRelease`) so it cannot decide for the next card.
- Letting go on no target is each surface's call: the Viewer's hold opens the picker, a lift is a pick-up and cancels, the deck's upward swipe saves to the top suggestion. A save swiped before targets load waits for them.
- A finger within touch slop of the pick-up hovers nothing and releasing opens the post menu. The end edge takes no hover until the first ranking arrives, since its targets shift then.
- Drops outlive the overlay's phases: a save or trail keeps running after the copy has landed, and Undo for a drop that created a Codex removes it.
- The lifted copy clears its semantics so TalkBack and tests find only the original card; the card's long-press modifier sits after its clickable so a release never also clicks, consumes the drag, and keeps an accessibility long-click that opens the menu.
- A feed update that disposes the card mid-lift cancels the lift.
- The triage deck offers TalkBack custom actions equal to the drop targets, and composes the next card only while the top card is away from rest so it holds no player.

## Consequences

Surfaces share the save menu but differ at "no target". New targets must be wired for both the drag path and the accessibility actions.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/lift/LiftSession.kt`: `LiftSession`, `hoverPointer`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/lift/LiftOverlay.kt`: `LiftOverlay`, `LiftStage`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/lift/LiftedCopy.kt`: `LiftedCopy`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/SaveMenuTargets.kt`: `saveMenuTargets`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCardLift.kt`: `rememberCardLiftModifier`, `CardLift`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageDeckArea.kt`: `TriageDeckArea`, `ReleasedOn`, `triageActions`, `TriageCards`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageDeckGesture.kt`: `release`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/ui/components/FeedPostCard.kt`: `FeedPostCard`
