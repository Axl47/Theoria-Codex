# 0100. Removals are hidden first and deleted when their lifetime ends, with one app-wide Undo offer

Status: Accepted

## Context

Undo has to survive a configuration change, must not let an unrelated notification commit a pending deletion, and must not delete anything if the process dies while an offer is showing. Accessibility services also recommend longer display times than the app's defaults.

## Decision

- `DeferredRemovals.remove` hides an item at once and deletes it through `commit` only when its lifetime ends, so Undo is a plain un-hide and a process death deletes nothing. Each key has its own timer, independent of the `UndoSlot`, so no unrelated offer commits a removal.
- Commits and timers run in an application-lived scope. The owning view model calls `commitAll` from `onCleared`, and that commit has to finish after the view model is gone. `commitAll` commits waiting removals; failed ones show again.
- `undo` puts back a waiting removal or dismisses a failed one. A running commit cannot be undone.
- `UndoSlot` holds at most one `UndoOffer` for the whole app (one per Activity). Nothing queues: a newer offer expires the one it replaces, so `UndoOffer.onExpire` must not do real work. Deletions that wait for their Undo to pass belong in `DeferredRemovals`.
- An offer's timer, `act` and `onExpire` run in the scope the owner passed to `offer`. If that scope is cancelled while the offer is live, the offer disappears and neither runs. An offer from an already-cancelled scope is dropped and leaves the live offer alone. Composables use the slot's own scope, which the app takes from its Activity-retained owner, so an offer survives a configuration change.
- Timed lifetimes are stretched to what accessibility services recommend (`UndoTimeouts.stretch`, applied through `UndoSlot.timeout`, switched on by `ProvideUndoSlot`).
- `SurfaceLeaveEffect` fires when its surface is left (navigation, closed sheet, finished Activity) but not when the Activity is only recreated for a configuration change.

## Consequences

Owners keep their own restore logic and only emit offers. Anything that deletes data must go through `DeferredRemovals`, never through `onExpire`. Changing the single-slot rule would require a queue and a policy for which offer wins.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/undo/DeferredRemovals.kt`: `DeferredRemovals`, `remove`, `undo`, `commitAll`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/undo/UndoSlot.kt`: `UndoSlot`, `UndoOffer`, `UndoTimeouts`, `offer`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/undo/UndoSurfaces.kt`: `ProvideUndoSlot`, `SurfaceLeaveEffect`
