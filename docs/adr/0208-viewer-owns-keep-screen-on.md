# 0208. The Viewer owns keep-screen-on

Status: Accepted

## Context

`PlayerView`s inflated from `media-kit`'s `player_view_texture.xml` set `android:keepScreenOn`, and Android keeps a window awake while any visible view asks. A paused or finished Viewer video, and any feed with an autoplaying card on screen, therefore held the screen on indefinitely, alongside the Viewer's own reading and slideshow rules.

## Decision

`ViewerKeepScreenOnEffect` is the only code that sets the flag during normal use. It keeps the screen on while the selected media is a video whose playback is playing and the Viewer is at least started, so picture-in-picture counts; and, as before, while a slideshow runs or a chapter is read without 10 idle minutes, only while the Viewer is resumed. Player views never request it themselves.

Feed cards autoplay muted as previews, so they never hold the screen on: nothing in the feed asked for it beyond the shared layout attribute.

The only other setters are the debug journey and benchmark fixture activities, which keep test runs awake.

## Consequences

A feed left open now sleeps on the system timeout while its cards keep playing. A new surface that plays video for the user to watch must route its request through the Viewer's effect, or a shared owner extracted from it, rather than a view attribute.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerKeepScreenOnEffect`
- `media-kit/src/main/res/layout/player_view_texture.xml`
