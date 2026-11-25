# 0165. Back during a return is held and replayed, and the Viewer keeps both destinations alive while media returns to its card

Status: Accepted

## Context

Closing a Viewer opened from a feed card animates the media back to that card. Nothing may pop the route underneath a return that is already moving, yet two quick Backs must still mean two steps back. The feed is not composed behind the Viewer, so the drag and return are drawn over a dimming background.

## Decision

- `ViewerHeldBackHandler` registers an overlay-priority handler, enabled while a dismiss is pending or the Viewer is returning. A Back pressed then sets `ViewerScreenState.heldBack` and is replayed (posted to the view, only if the host is at least STARTED) when the Viewer leaves composition at the end of the return. At most one held Back runs. A hold that ends while the Viewer stays, such as a new Viewer opening and clearing the return, drops it.
- The hold outranks every ordinary Back handler, including those the screen underneath registers as it returns, and no lifecycle turns it off, since popping the Viewer stops its destination before the return ends. The replay is posted so every handler leaving with the Viewer is gone first.
- A predictive-back gesture that is cancelled recoils in the screen's scope, because cancelling cancels the collector; a second Back that commits before the frame that enables the hold is held like one the hold caught.
- `ViewerReturnLifetime` keeps both destinations alive while the media moves between card and fullscreen bounds, read as an exit starts; an exit to another Viewer level, or from a Viewer opened without a card, skips the hold, since no return runs ([0218](0218-screens-leave-as-one-opaque-unit.md)). `ViewerCoveredLifecycle` caps a covered level's lifecycle at CREATED so its playback stops when the covering level opens, not when the transition ends; a destroyed host always wins, and the registry never returns to INITIALIZED.
- `ViewerDismissDrag` only shrinks the media over its dimming background; on release the return transition takes over from wherever the media was left. `rememberViewerReturnProgress` is one value (0 fullscreen, 1 feed card) read only while drawing, since reading it in composition recomposes the whole Viewer every frame. The landing target is chosen when the media starts moving so a card appearing later cannot make it jump.
- `rememberPresentedSession` keeps the last session while a closing Viewer has none, so pages, players and positions stay as they were for the return; a rebuilt player would start over at 0 and report that as the episode's position.

## Consequences

Back handling during returns depends on the navigation-event overlay priority. Every Viewer exit path must go through the hold.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerHeldBackHandler`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenState.kt`: `ViewerScreenState.heldBack`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransition.kt`: `ViewerReturnLifetime`, `ViewerCoveredLifecycle`, `ViewerDismissDrag`, `rememberViewerReturnProgress`, `ViewerLandingTarget`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `rememberPresentedSession`
