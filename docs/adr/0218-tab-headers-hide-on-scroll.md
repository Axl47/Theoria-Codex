# 0218. Tab headers hide on scroll by shifting the content

Status: Accepted

## Context

The owner asked for headers that leave on scroll-down and return on scroll-up, so grids get the screen. Every
calculation that trusts a list's viewport (Viewer return reveals, Codex reorder auto-scroll, page demand,
centered empty states) needs that viewport to match what is on screen, and feed cards hold playback leases while
laid out (ADR 0119), so a laid-out card that is not visible costs a decoder. Scrolling must not relayout more than
the list already does. The Search and Series corner strips (Expand Search, the source picker and the Settings
button) must stay put. The Viewer has its own chrome rules and is out of scope.

## Decision

- `ScrollChromeBox` is the one owner. It slides its whole content up by the current hide offset and measures it
  taller by that same offset, so each list's viewport always equals what is visible: Viewer return reveals, Codex
  reorder auto-scroll, end-of-list paging and centered empty states keep working unchanged. A resize happens only
  during the few frames the header is moving; once it is shown or hidden, scrolling relayouts nothing extra.
  Measuring the content a full header-height taller instead was rejected: the viewport then reached below the
  visible edge, so short lists hid their last row and every viewport-based calculation was off by that height.
- The range is the lowest bottom edge among elements marked `Modifier.hidesOnScroll()`, measured in the content's
  own coordinates, so the translation cancels out and a header that changes size updates the range once.
  Anything outside the box, such as the overlay row of `CollapsingSearchChrome`, stays pinned.
- `ScrollChromeState` takes a scroll-up first, so any scroll-up reveals the header before the list moves, and
  the header follows the finger 1:1. A scroll-down at the list's top goes to the list and pull-to-refresh
  (`preScroll`), so a list that fits on screen never hides its header. When the list stops scrolling, or a fling
  ends, the header settles toward the last direction, but never stays hidden while the list is at its top.
  A list that only fits because the hidden header gave it room is at its end, not its top
  (`ScrollChromeState.atTop`): it counts as at its top only when the empty space below its last item covers
  the hidden part of the header, so a list that shrinks still brings the header back. Otherwise hiding the
  header would make a short list, such as Library's Saved shelves, report its top, bring the header back and
  push its last row below the screen every time it reached the end. Such lists fill the box's height, so a drag
  that starts below their last item still reaches them.
- Lists bind to the header through `ScrollToTopOnTabReselect(destination, scrollable)`, which every tab list
  already calls: it scrolls to the top and reveals the header on reselect, reports the list's top
  (`ScrollChromeTopEffect`, which also catches programmatic scrolls), and reveals the header when the list
  leaves, such as for an empty state.
- Hiding is off, and the header shown, while its tab is not shown (`LocalTabShown`, provided per page by
  `HomePager`, because the pager can keep a page's state across a switch), while the screen is stopped (under the
  Viewer), while touch exploration is on (`rememberTouchExplorationEnabled`), and while a hideable holds keyboard
  focus. So TalkBack and keyboard focus never rest on an off-screen control.
- Wired screens: Series (view switch, Following controls, Browse header), For You in grid mode (bar,
  crumbs, failure row), and Library (the shared `LibraryHeader`). Search keeps its existing collapse into the
  corner strip, and the bottom navigation bar stays pinned at the owner's choice.

## Consequences

Cards revealed by the hide are laid out, and so start playing, as they come into view, exactly as when scrolling
them in; nothing laid out is off-screen, so every visible card autoplays and no extra decoders run. Hiding the
bottom bar would need every bottom-anchored control (filter FAB, undo pills, snackbars) to track its offset, which
is why it was left out.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/ScrollChrome.kt`: `ScrollChromeState`, `ScrollChromeBox`, `hidesOnScroll`, `ScrollChromeTopEffect`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/TouchExploration.kt`: `rememberTouchExplorationEnabled`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabScreen.kt`: `SeriesTabHomeContent`, `SeriesViewSwitch`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckScreen.kt`: `ForYouDeckScreen`
- `feature-library/src/main/kotlin/com/theoriacodex/app/library/LibraryHeader.kt`: `LibraryHeader`
- `app/src/main/java/com/theoriacodex/app/ui/LibraryDestination.kt`: `LibraryDestination`
- `app/src/main/java/com/theoriacodex/app/ui/routes/SavedCodexRoute.kt`: `SavedCodexRoute`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaHomeDestinations.kt`: `HomePager`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/SharedNavigation.kt`: `LocalTabShown`, `ScrollToTopOnTabReselect`

## Guarded by

- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `scrolling down hides the header with the finger and stops at its height (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `any scroll up reveals before the list moves (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `release settles toward the last direction but never hides at the top (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `the lowest hideable sets the range and an inactive chrome never hides (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `show returns the header at once (ADR 0218)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabScreenTest.kt`: `scrollingDownHidesTheViewSwitchUntilAnyScrollUpWhileSearchAndSettingsStayPinned_adr_0218`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/HeaderHidesOnScrollJourneyTest.kt`: `theLibraryHeaderLeavesOnScrollDownAndReturnsOnScrollUpAndAfterATabSwitchWhileTabsStay_adr_0218`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `at the top a downward scroll goes to the list and pull to refresh, never the header (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `a focused header control keeps the header shown (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `a list that fits only once the header hides keeps it hidden at its end (ADR 0218)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/library/LibraryHeaderScrollTest.kt`: `a short section that fits once the header hides keeps its last row in view at the end (ADR 0218)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/library/LibraryHeaderScrollTest.kt`: `a section that shrinks to fit under the shown header brings a hidden header back (ADR 0218)`
- `ui-kit/src/test/kotlin/com/theoriacodex/app/ui/components/ScrollChromeStateTest.kt`: `a list that fits even with the header shown is at its top, so a hidden header returns (ADR 0218)`
- Left to prose: that a list's viewport equals its visible area is a layout property, covered by the Library journey reaching its header and lists on a device rather than by a host test.
