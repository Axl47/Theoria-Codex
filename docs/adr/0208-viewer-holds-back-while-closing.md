# 0208. The Viewer holds a Back pressed while it closes, and its dismissal never suspends

Status: Accepted

## Context

Closing the Viewer moves its media back into the card it came from, and nothing may pop the screen underneath while
that return is moving. A second Back pressed during the return used to be swallowed, so two quick Backs left only
the Viewer.

The Viewer counts as leaving while `pendingDismiss` is set or a return is in progress. A Back caught then is held and
replays once the Viewer leaves; a hold that ends while the Viewer is still composed drops it. The route's dismissal
clears the session, pops the Viewer and begins the return. When the dismissal suspended between clearing the session
and beginning the return (it used to wait for the launch context to be written), the cleared session recomposed in
between and reset `pendingDismiss`. For a frame the Viewer was neither dismissing nor returning, so the hold ended and
dropped the held Back. `HeavyLibraryJourneyTest.twoQuickBacksFromTheViewerLandTwoScreensBack` failed this way on
API 37 CI.

## Decision

While the Viewer is leaving, a navigationevent handler at `PRIORITY_OVERLAY` catches Back and keeps at most one,
replaying it once the Viewer leaves composition. The overlay priority outranks the handlers the screen underneath
registers as it returns, and no lifecycle turns the hold off, because popping the Viewer stops its destination before
the return ends.

`ViewerRouteEffectCallbacks.onDismiss` is not a suspend function, so the route clears the session, pops and begins the
return without yielding, and the Viewer never recomposes in between. Work the dismissal does not need to wait for runs
in the shell's scope; the launch context it clears is read only when a Viewer is rebuilt after process death.

## Consequences

Two quick Backs during the Viewer's return to its card mean two steps back, however the dismissal is triggered. A dismissal that needs to wait for something before it pops would reopen
the gap. It would then need a state that marks the Viewer as closing until it leaves, owned by the reducer rather
than inferred from session changes.

A back swipe started during the return and still held when the Viewer leaves is cancelled with the Viewer.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerHeldBackHandler`, `ViewerBackHandlers`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt`: `ViewerRouteEffectCallbacks.onDismiss`, `handleViewerEffect`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: the Viewer's `onDismiss`
