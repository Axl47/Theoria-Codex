# 0218. Screens leave as one opaque unit, and a Viewer with no card to return to leaves at once

Status: Accepted

## Context

Two exits showed the screen behind through a screen that was still leaving.

The Viewer's close runs the return that flies the current media back into the card it opened from: the destination
holds the Viewer composed for `VIEWER_RETURN_HOLD_MS`, the return waits up to `VIEWER_RETURN_TARGET_WAIT_MS` for the
card's bounds, then fades the black backdrop while the media moves. A Viewer opened from a series page, a
notification or anything else that is not a feed card has no card: the wait always timed out, the backdrop faded
over the screen underneath while the media slid down on its own, and a vertical gallery, which did not follow the
return at all, stayed on screen until the hold ended and then vanished.

navigation-compose drives the system back gesture with its own `predictivePopEnterTransition` and
`predictivePopExitTransition`, not with the pop transitions. Without them the gesture faded the destination in
behind the leaving screen, and screens that draw no background of their own, such as the series page, showed their
text over it.

## Decision

`ViewerReturnTransition.begin` starts a return only when `openedFrom` recorded a card since the last `clear`, and
`ViewerReturnLifetime` holds the Viewer's exit only while a return is running. A Viewer opened from no card therefore
leaves in one frame, the same way it opens. A vertical gallery applies `viewerReturnMedia` like a paged page, so it
lands in its card together with the backdrop.

`TheoriaScreenHost` owns the NavHost's transitions and passes the pop transitions as the predictive ones too, so the
back arrow, the back gesture and in-app pops animate the same way. Destinations other than the Viewer and the Codex
detail, which draw their own background, are declared with `opaqueScreen`, which puts the theme background under
the screen so it can never be seen through while it moves.

## Consequences

A close from a series chapter or episode has no animation, matching its entry. Giving it one would need a matching
entry and a whole-Viewer exit, never the card return. A new full-screen destination should use `opaqueScreen`;
one that must show what is beneath it would need its own transitions that never overlap another screen.

A Viewer reached from a feed card and then reused for a launch with no card (another Viewer still in the back stack)
keeps the stale origin and closes with the card return's slide-down fallback, as before.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnContract.kt`: `ViewerReturnTransition.begin`, `ViewerReturnTransition.openedFrom`, `ViewerReturnTransition.clear`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransition.kt`: `ViewerReturnLifetime`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGallery.kt`: `ViewerVerticalGallery`
- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `TheoriaScreenHost`, `opaqueScreen`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `theoriaDestinations`

## Guarded by

- `app/src/test/java/com/theoriacodex/app/ui/ScreenExitFramesTest.kt`: `leaving a screen with the back arrow never blends it with the screen underneath (ADR 0218)`
- `app/src/test/java/com/theoriacodex/app/ui/ScreenExitFramesTest.kt`: `leaving a screen with the back gesture never blends it with the screen underneath (ADR 0218)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `a Viewer opened without a card leaves in one frame when it closes (ADR 0218)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `a vertical gallery closing into its card moves into the card with the backdrop (ADR 0218)`
- Left to prose: that every new full-screen destination uses `opaqueScreen`; a policy rule in `scripts/check_architecture.py` could require it for `composable(` calls in `TheoriaNavDestinations.kt`.
