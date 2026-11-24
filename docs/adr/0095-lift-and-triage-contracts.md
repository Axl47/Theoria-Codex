# 0095. Card lift and triage hand-offs keep the long-click as the fallback and carry the grid's view

Status: Accepted

## Context

Feed cards can lift into a drag-to-collect gesture or open the triage deck. Both are provided from outside the ui-kit, and a card must behave sanely when nothing provides them.

## Decision

- `LiftController.start` returns false when no lift started, and the card then keeps its long-click. `LiftController.None` never lifts. While `lifting` is true, another card's long press must not start or open anything.
- `TriageSeed` hands the deck what the grid shows when Triage is tapped, as a grid hands posts to the Viewer: visible posts in order, the grid's media, mark and date filters (which a Viewer opened from the deck applies), and `filterInRange`, so a copy with another date range filters by that one.
- `rememberTriageAction` returns null, so the filter sheet offers no Triage, when there is no feed key, the grid shows no posts, or nothing provides a `LocalTriageOpener`.

## Consequences

Cards never lose their menu when lifting is unavailable. Changing the seed means keeping the deck and the Viewer's filtering consistent.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/navigation/LiftController.kt`: `LiftController`, `LiftController.None`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/navigation/TriageOpener.kt`: `TriageSeed`, `rememberTriageAction`
