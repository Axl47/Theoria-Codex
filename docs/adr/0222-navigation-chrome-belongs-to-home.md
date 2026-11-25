# 0222. The bottom bar and rail belong to Home, and the screen area never changes size

Status: Accepted

## Context

The bottom bar (phones) and navigation rail (wide windows) used to live in the app's scaffold, around the NavHost,
and appeared whenever Home became the current route. A pop back to Home makes Home current as soon as the pop
starts, so the scaffold added the bar mid-slide and shrank the NavHost under the screen that was still leaving. The
system back gesture was worse: its preview runs while the leaving screen is still current, so it showed Home without
its bar and then resized everything when the gesture committed. Driving the bar's visibility from the transition
instead would still draw it over the leaving screen, which is not part of Home.

## Decision

- `TheoriaAdaptiveScaffold` knows nothing about routes or navigation chrome. It insets the screen area for the top and
  side system bars only, so the NavHost has the same bounds on every route and through every transition. It keeps
  the snackbar host and lifts it above the bottom bar while Home is current (`snackbarLift`), animated so a pill
  that is showing glides rather than jumps.
- Home draws its own chrome: its destination wraps `HomePager` in `TheoriaNavigationChrome`, which lays out the rail
  beside it or the bar below it. The chrome therefore enters, leaves, previews and cancels with Home in every
  transition, the back gesture's preview included, and a screen pushed over Home covers it like any other part of
  Home.
- The NavHost reaches the bottom edge so the bar's surface can extend under the system navigation bar. Every other
  screen takes the bottom system-bar inset itself (`screenBottomInsets`): `opaqueScreen` does it inside its
  background unless declared with `reachesBottomEdge`, which only Home is, and the Codex detail and the Viewer
  destination do it around their content. Home in rail mode insets its content the same way, and the rail extends
  to the bottom edge.

## Consequences

A new destination declared with `opaqueScreen` gets the bottom inset for free; one declared with a bare
`composable` must apply `screenBottomInsets` itself or its content will run under the system navigation bar. On
wide windows the rail now starts below the status bar instead of under it.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAdaptiveScaffold.kt`: `TheoriaAdaptiveScaffold`, `TheoriaNavigationChrome`, `screenBottomInsets`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`: `TheoriaNavigationSurface`, `TheoriaNavHost`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `theoriaDestinations`, `CodexDetailEntry`
- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `opaqueScreen`

## Guarded by

- `app/src/test/java/com/theoriacodex/app/ui/AdaptiveNavigationTest.kt`: `a screen leaving for Home keeps its size while Home already shows its bar (ADR 0222)`
- `app/src/test/java/com/theoriacodex/app/ui/AdaptiveNavigationTest.kt`: `the rail belongs to Home, so other screens keep the full width (ADR 0222)`
- Left to prose: that every bare `composable` destination applies `screenBottomInsets`; host tests run without system-bar insets, and a policy rule in `scripts/check_architecture.py` could require it for `composable(` calls in `TheoriaNavDestinations.kt`.
