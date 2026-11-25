# 0218. Tab headers hide on scroll by translation, not by resizing

Status: Accepted

## Context

The owner asked for headers that leave on scroll-down and return on scroll-up, so grids get the screen. The
obvious implementations shrink the header's measured height or animate a spacer. Either one changes the grid's
viewport every frame: the grid relayouts, cards jump between columns, and the set of laid-out cards, which holds
the feed's playback leases (ADR 0119), changes mid-scroll. The Search and Series corner strips (Expand Search,
the source picker and the Extensions button) must stay put. The Viewer has its own chrome rules and is out of
scope.

## Decision

- `ScrollChromeBox` is the one owner. It reads vertical nested-scroll deltas before its child list does and
  slides its whole content up as one graphics-layer translation (`placeWithLayer`), so a scroll only redraws.
  It measures the content taller by the hideable range and clips it, so the bottom edge never opens a gap and
  the grid's viewport stays the same size whether the header is shown or hidden.
- The range is the lowest bottom edge among elements marked `Modifier.hidesOnScroll()`, measured in the content's
  own coordinates, so the translation cancels out and a header that changes size updates the range once.
  Anything outside the box, such as the overlay row of `CollapsingSearchChrome`, stays pinned.
- `ScrollChromeState.consume` takes the delta first in both directions, so the header follows the finger
  1:1. Any scroll-up starts revealing it before the list moves. On release it settles toward the last direction,
  but never stays hidden while the list is at its top (`ScrollChromeTopEffect`, which also catches programmatic
  scrolls such as tab reselect).
- The header returns when its tab is left (`LocalTabShown`, provided per page by `HomePager`, because the pager can keep a page's state across a switch) and when the screen stops, such as under the Viewer (`LifecycleStartEffect`). A focused
  hideable shows it, and with touch exploration on (`rememberTouchExplorationEnabled`) nothing hides, so TalkBack
  and keyboard focus never rest on an off-screen control.
- Wired screens: Series (view switch, Following controls, Browse header), For You in grid mode (bar, chips or
  crumbs, failure row), and Library (the shared `LibraryHeader`). Search keeps its existing collapse into the
  corner strip, and the bottom navigation bar stays pinned at the owner's choice.

## Consequences

While the header shows, about one header-height of cards sits laid out below the visible edge, so those cards
hold playback leases and are already playing when the header leaves. That costs a row of extra decoders, never a
stall, and it keeps every visible card autoplaying. At the very end of a list, the last header-height is revealed
by the hide rather than by scrolling. Centered empty states sit half a header-height lower than before. Hiding
the bottom bar would need every bottom-anchored control (filter FAB, undo pills, snackbars) to track its offset,
which is why it was left out.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/ScrollChrome.kt`: `ScrollChromeState`, `ScrollChromeBox`, `hidesOnScroll`, `ScrollChromeTopEffect`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/TouchExploration.kt`: `rememberTouchExplorationEnabled`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabScreen.kt`: `SeriesTabHomeContent`, `SeriesViewSwitch`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckScreen.kt`: `ForYouDeckScreen`
- `feature-library/src/main/kotlin/com/theoriacodex/app/library/LibraryHeader.kt`: `LibraryHeader`
- `app/src/main/java/com/theoriacodex/app/ui/LibraryDestination.kt`: `LibraryDestination`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaHomeDestinations.kt`: `HomePager`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/SharedNavigation.kt`: `LocalTabShown`

## Guarded by

- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `scrolling down hides the header with the finger and stops at its height (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `any scroll up reveals before the list moves (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `release settles toward the last direction but never hides at the top (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `the lowest hideable sets the range and an inactive chrome never hides (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `show returns the header at once (ADR 0218)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabScreenTest.kt`: `scrollingDownHidesTheViewSwitchUntilAnyScrollUpWhileSearchAndExtensionsStayPinned_adr_0218`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/HeaderHidesOnScrollJourneyTest.kt`: `theLibraryHeaderLeavesOnScrollDownAndReturnsOnScrollUpAndAfterATabSwitchWhileTabsStay_adr_0218`
- Left to prose: that hiding never resizes the grid's viewport is a layout property; a host test would need to measure the grid across frames, and the device frame-timing check covers it instead.
