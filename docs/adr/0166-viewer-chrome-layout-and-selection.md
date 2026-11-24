# 0166. Viewer chrome composes in stages, floating controls follow the footer, and the selected media drives layout

Status: Accepted

## Context

Showing chrome over playing video must not cost one long frame. The footer fades in and out with the chrome, floating buttons must not jump with it, and several effects must stay in step with the selected post and page while swipes, scrubs and feed updates move underneath them.

## Decision

- `rememberViewerChromeStage`: chrome parts arrive one frame apart, so showing costs several short frames instead of one long one while video plays. Hiding is immediate.
- `ViewerFooterButton` floats `VIEWER_FOOTER_BUTTON_GAP` above the footer the current media shows and glides over the same span as the footer's fade between the footer's edge and the screen's edge, instead of jumping. `viewerFooterHeight` is shared with subtitle bottom clearance (`LocalViewerSubtitleBottomLift`). The dock's inset already follows the chrome; the timeline or page scrubber and the row above it hide with it.
- While scrubbing, everything but the timeline steps back through alpha only, so the strip keeps its scroll position (`scrubFade`). A hold-scrub needs the timeline on screen: `beginScrub` records whether it had to show hidden chrome, and `endScrub` hides by value (`SetChromeVisible(false)`) rather than toggling, because begin and end can share one frame's controls whose visibility is stale. `markPassiveInteraction` keeps open controls alive for seeking, zooming and navigation but never opens them; only a tap does.
- A page a swipe heads to shows its timeline too, because the swipe only selects it once the slide settles. Trail forks leave when a swipe heads to another post, since the selection changes only once the swipe lands and a fork returning during the slide would open over the post being left. A caught swipe commits while its pages are still moving, so only a resting pager follows selection. Selecting another page already closes the overview in the reducer; a feed page arriving must not.
- `ViewerControls` is replaced (`rememberViewerControls`) when its values change, since an instance held across a change would toggle chrome or dismiss against a passed state. `ViewerSelection.mediaItems` is keyed by post so a readiness change rebuilds neither the media list nor its kinds. A new slideshow returns zoomed media to fit so the next touch reaches the gesture that stops it.
- `systemControlsReady` is false while the Viewer opens (connecting system controls costs frames) and once its session ended, because a closing Viewer's paused pages stay for the return transition and a system Play would start the closed Viewer's player.
- Layout of motion media: still images always fill the page; animated and video media sit under a 40 dp portrait top gap clear of the top bar unless the layout is edge to edge (landscape, picture-in-picture or reading a chapter). A post whose motion resolves only after opening (`Post.awaitsMotion`) keeps the gap while it shows its poster, so it does not shift when the stream arrives.
- `rememberViewerTouchExploration` tracks accessibility-service changes while the Viewer is open without making playback depend on accessibility.
- Accent: `viewerAccentFromPixels` samples the thumbnail at 48 px so small vivid details survive downscaling, buckets hues, weights vividness over area, discounts skin hues (10-50 degrees, saturation below 0.7, weight 0.15) because they would give nearly every character art the same peach, returns null (white accent) when fewer than 6% of pixels are colourful (monochrome art such as manga), then fixes saturation and lightness so the accent reads over dark scrims. Sampling waits for `enabled` so it never competes with the opening animation, and swiping past a post cancels its sample.

## Consequences

New chrome parts need a stage. The footer-height helper is the single source for anything that must stay clear of the bottom stack.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `rememberViewerChromeStage`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFooterButton.kt`: `ViewerFooterButton`, `viewerFooterHeight`, `viewerFooterBarHeight`, `LocalViewerSubtitleBottomLift`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenState.kt`: `ViewerControls`, `ViewerSelection`, `ViewerFrame`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenChrome.kt`: `ViewerChromeOverlays`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerNavigationEffects`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostPager.kt`: `ViewerPostPage`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaPage.kt`: `viewerMediaTopGap`, `Post.awaitsMotion`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAccessibility.kt`: `rememberViewerTouchExploration`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerArtworkAccent.kt`: `viewerAccentFromPixels`, `rememberViewerAccent`
