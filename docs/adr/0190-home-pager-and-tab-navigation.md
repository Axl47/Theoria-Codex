# 0190. The home tabs are one pager whose swipes, taps and bar sizing follow fixed rules

Status: Accepted

## Context

The top-level tabs sit side by side in a pager that a swipe or a bar tap can move. A scroll to a far tab passes over the tabs between, a sideways drag on the For You deck decides a card, and Android's navigation mode changes the bottom inset. Library replaced earlier tabs, so a stored last-tab route can name a tab that no longer exists.

## Decision

- `topLevelPagerFling` uses `TheoriaMotion.PAGE_COMMIT_FRACTION`, so slow tab swipes commit as readily as Viewer swipes; the library default needs half the screen.
- `tabShown` reports a tab as shown when the pager rests on it or is heading to it (target or settled page). A bar tap from Search to Library scrolls across For You without ever targeting it; a swipe toward a tab targets it as soon as it is under way.
- `tabTap` decides a bar tap: a tap on the settled tab reselects it, a tap on the tab a scroll is already heading to lets that scroll finish, and any other tap moves there. It is never decided from the nearest page: a scroll to another tab passes over the tabs between, and a tap on one of those is a tab change.
- A move whose target is exactly the current page uses `scrollToPage` instead of `animateScrollToPage`: a scroll passing exactly over the target makes `animateScrollToPage` return without stopping that scroll, which then carries on past it.
- `tabSwipeEnabled` is false while For You shows its deck, where a sideways drag decides a card; there the tabs change from the bottom bar only. For You's grid swipes like the other tabs, because a pager that takes no drags leaves them to end as taps on whatever they started on.
- A bar tap on the Library tab while it is lit with new posts (and not already showing) selects the Saved section, where new posts wait; without the dot the remembered section stays.
- The For You tab icon is `AutoAwesome` (sparkles) rather than a heart, which would read as Likes.
- `calculateBottomNavigationSizing` keeps the bar at the established gesture-navigation size: the standard 24 dp gesture area (`INCLUDED_GESTURE_INSET_DP`) is already part of the base height, so only the inset beyond it (Android's larger three-button inset) adds height below the controls.
- A Codex opens by growing out of its tile, so `codexEnterTransition` and `codexExitTransition` only fade, above the screen beneath it. That screen stays opaque throughout: fading both would let whatever is behind them show through. `CodexDetailEntry` sends system Back through the same animated `popBackStack` as the toolbar, so both close the Codex by shrinking it back into its tile.
- `TheoriaNavigationChrome` keeps one stable content slot, which preserves navigation owners and scroll state while the window resizes. `TheoriaAdaptiveScaffold` insets the screens for the top and side system bars and each screen takes the bottom one ([0222](0222-navigation-chrome-belongs-to-home.md)), so tab screens such as `ForYouDeckScreen` add no system-bar padding of their own. Its container color is black on the Viewer route and the theme background elsewhere, because the status bar's inset shows that color and the Viewer letterboxes in black.
- Every cross-tab jump selects its tab with `showTab`, which moves the pager without a page animation, since one would pass over the tabs between. `showTab` records `homeTabRoute` and scrolls only while Home is current; with another screen on top, `HomeTabEffects` scrolls the pager there when Home returns. `goToTab` first pops back to Home (or navigates there) and then calls `showTab`.
- Stored last-tab routes from before Library existed (`recents`, `codex`, `settings`) are kept in `legacyTabSections`: they restore to Library at the History section, the Codices section, or (for \`settings\`) Library's own section. `restoredHomeTab` opens Library for any retired tab and Search for anything unknown.

## Consequences

A new tab must be considered in `tabTap`, `tabShown` and the saved-route restore. Changing the For You deck's gesture model changes `tabSwipeEnabled`. Dropping a retired route from `legacyTabSections` sends users who last left the app on it to Search.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `topLevelPagerFling`, `tabShown`, `TabTap`, `tabTap`, `tabSwipeEnabled`, `codexEnterTransition`, `codexExitTransition`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAdaptiveScaffold.kt`: `TheoriaAdaptiveScaffold`, `TheoriaNavigationChrome`, `navigateTopLevelDestination`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`: `TheoriaNavigationSurface` (`onDestinationSelected`, `containerColor`)
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShell.kt`: `showTab`, `goToTab`, `homeTabRoute`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShellEffects.kt`: `HomeTabEffects`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckScreen.kt`: `ForYouDeckScreen`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `CodexDetailEntry`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppChrome.kt`: `TabIcon`
- `app/src/main/java/com/theoriacodex/app/ui/BottomNavigationSizing.kt`: `calculateBottomNavigationSizing`, `INCLUDED_GESTURE_INSET_DP`
- `app/src/main/java/com/theoriacodex/app/ui/LibrarySections.kt`: `legacyTabSections`, `restoredHomeTab`
