# 0220. Screens leave as one opaque unit, and a Viewer with no card to return to leaves at once

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

A card tap inside a return grid records its origin with `openedFrom`, naming the tapped post; a tap outside any grid
records none, since nothing would answer the return. Every Viewer launch goes through
`TheoriaShell.launchViewer`, which hands the origin to `ViewerReturnTransition.launching`: a launch that leaves the
root Viewer in place (a trail fork pushed over it, or a single-top launch that replaces only a fork) keeps the root's
origin, and any other launch keeps it only when its posts include the tapped post, clearing it otherwise. A Codex in
edit mode gives its cards no return transition, since a tap there selects instead of opening. A tap that opened no
Viewer therefore never lends its card to the next launch, whatever screen that launch starts from. `ViewerReturnTransition.begin` starts a return
only while an origin is recorded, and `ViewerReturnLifetime` holds the Viewer's exit only while a return is running.
A Viewer opened from no card therefore leaves in one frame, the same way it opens.

The origin belongs to the root Viewer's back stack entry, not to its composition: `ViewerReturnOriginLifetime`, which
only a root Viewer composes (one not stacked on another Viewer, so a trail opened from a full screen counts), adopts the pending `ViewerReturnOrigin` (or an empty one) when the entry was created in an entry-scoped
ViewModel and releases it when the entry leaves the back stack, which navigation does only after the closing hold ends.
Each origin carries its own grid and shelf, and `ViewerReturnTransition` keeps one per root Viewer in back stack order;
the newest is the one a close returns to. A tap or launch replaces only an origin no Viewer has adopted yet, and a release
removes only that Viewer's origin, so a card tapped during the hold keeps its own, and a Viewer under a second root Viewer
(Feed, Viewer, creator profile, Viewer) closes into its own card once the second one leaves. A Viewer covered by a full screen, such as a creator profile or a
series page, or by a trail fork, therefore still closes into its card when the user comes back to it, and a covered
Viewer removed from the back stack without being shown again lets its card go. An opening cut short, as when a full
screen covers the Viewer at once, still ends, but only for the origin it opened from (`finishOpening`).

Feeds keep the Viewer's posts past their hide filters (`retainPosts`) only while the Viewer is composed: the route
retains them in every composition under its own `ViewerPostRetention`, releases them when it leaves composition, at the
end of the closing hold or when a full screen covers it, and retains them again when it comes back. Feeds see the
union of every route's set, so a Viewer opened during the hold keeps its own. A Viewer opened with no card, or a screen covering a
Viewer, therefore never shows posts its filters hide.

A Triage or For You deck card is a return target of its own: `ViewerReturnCard` gives the top card a grid id and
reports it positioned at once, since the card is already on screen. A Viewer closed on another post than the deck's
top card falls back to the slide-down, as a grid does for a post it no longer holds.

A vertical gallery fades with the backdrop instead of following `viewerReturnMedia`. That modifier crops the middle
of whatever it is applied to, which for a scrolling list is a seam between pages rather than the selected one, so
the card would show the wrong picture.

`TheoriaScreenHost` owns the only transition table, the Viewer's included, and passes the pop transitions as the
predictive ones too, because navigation-compose never asks a `composable` for its gesture transitions. The back
arrow, the back gesture and in-app pops therefore animate the same way. Every destination except two is declared
with `opaqueScreen`, which puts the theme background under the screen, with the constraints the NavHost gave it, so
it can never be seen through while it moves. The Codex detail draws its own background inside its morph, and the
Viewer is excluded because its return fades its backdrop over the screen beneath.

## Consequences

A close from a series chapter or episode has no animation, matching its entry. Giving it one would need a matching
entry and a whole-Viewer exit, never the card return. A new full-screen destination should use `opaqueScreen`;
one that must show what is beneath it would need its own transitions that never overlap another screen.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnContract.kt`: `ViewerReturnTransition.openedFrom`, `ViewerReturnTransition.launching`, `ViewerReturnTransition.begin`, `ViewerReturnTransition.release`, `ViewerReturnTransition.retainPosts`, `ViewerReturnTransition.releasePosts`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaViewerLaunch.kt`: `launchViewer`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransition.kt`: `ViewerReturnLifetime`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGallery.kt`: `ViewerVerticalGallery`
- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `TheoriaScreenHost`, `opaqueScreen`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShell.kt`: `TheoriaShell.viewerReturn`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailScreen.kt`: `CodexSelectablePostCard`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransition.kt`: `ViewerReturnOriginLifetime`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `ViewerDestination`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt`: `RetainViewerPosts`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReturnContract.kt`: `ViewerReturnCard`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageDeckArea.kt`: `TriageCards`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `theoriaDestinations`

## Guarded by

- `app/src/test/java/com/theoriacodex/app/ui/ScreenExitFramesTest.kt`: `leaving a screen with the back arrow never blends it with the screen underneath (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ScreenExitFramesTest.kt`: `leaving a screen with the back gesture never blends it with the screen underneath (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ScreenExitFramesTest.kt`: `a back gesture out of the Viewer leaves it in place instead of sliding it like a screen (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `a Viewer opened without a card leaves in one frame when it closes (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `a card origin belongs only to the launch of the card that was tapped (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `a vertical gallery closing into its card is gone once the backdrop is (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `a Viewer covered by a full screen still closes into its card (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `the card origin outlives the Viewer's closing hold and ends with it (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `a Viewer leaving the back stack never takes a newer Viewer's card (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `a covered Viewer removed from the back stack lets its card go (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `a Viewer under a second card-opened Viewer still closes into its own card (ADR 0220)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerReturnOriginLifetimeTest.kt`: `a Viewer under a trail opened from a full screen still closes into its own card (ADR 0220)`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/ViewerReturnJourneyTest.kt`: `aViewerUnderASecondCardOpenedViewerStillClosesIntoItsCard_adr_0220`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/triage/TriageCardsTest.kt`: `a Viewer opened from the top card closes into that card (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `a Viewer that leaves releases only the posts it retained (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `an older Viewer's opening never ends a newer Viewer's (ADR 0220)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerReturnTransitionTest.kt`: `a tap outside any return grid records no card to close into (ADR 0220)`
- Left to prose: that `ViewerRoute` releases its retained posts when it leaves composition; no host test composes the route under a covering screen.
- Left to prose: that every new full-screen destination uses `opaqueScreen`; a policy rule in `scripts/check_architecture.py` could require it for `composable(` calls in `TheoriaNavDestinations.kt`.
- Left to prose: that every Viewer launch passes through `launchViewer`; a new `navigate(AppRoute.Viewer)` elsewhere would skip `launching`.
- Left to prose: that a Codex in edit mode records no origin; no host test composes the Codex detail grid, and the launch binding above still clears such an origin for any launch of another post.
