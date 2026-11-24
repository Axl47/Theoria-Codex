# 0208. The Viewer owns keep-screen-on

Status: Accepted

## Context

`PlayerView`s inflated from `media-kit`'s `player_view_texture.xml` set `android:keepScreenOn`, and Android keeps a window awake while any visible view asks. A paused or finished Viewer video, and any feed with an autoplaying card on screen, therefore held the screen on indefinitely, alongside the Viewer's own reading and slideshow rules.

## Decision

`viewerKeepsScreenOn` is the only code that decides it during normal use, and the Viewer's backdrop applies Compose's `Modifier.keepScreenOn()` while it holds. It keeps the screen on while a Viewer video's player is actually playing (`Player.isPlaying`, counted through `LocalViewerPlayingVideos`) and the Viewer is at least started, so picture-in-picture counts; and, as before, while a slideshow runs or a chapter is read without 10 idle minutes, only while the Viewer is resumed. Player views never request it themselves.

The video rule reads the player, not the reducer's play intent: ExoPlayer pauses on its own for audio-focus loss, when the Viewer pauses outside picture-in-picture, and on errors, and none of those reach the intent. A renderer that is unmounted, such as behind the page overview, stops counting with it. A long buffering stall lets the screen sleep on the system timeout.

Looping clips keep the screen on for as long as they play, unlike GIFs and animated WebP, which never request it.

The modifier counts requests per Compose owner, unlike `View.keepScreenOn`. During a trail transition two Viewers are composed under the same owner and the incoming one can request at started; a plain view flag would be cleared by the outgoing Viewer's disposal.

Feed cards autoplay muted as previews, so they never hold the screen on: nothing in the feed asked for it beyond the shared layout attribute.

The only other setters are the debug journey and benchmark fixture activities, which keep test runs awake.

## Consequences

A feed left open now sleeps on the system timeout while its cards keep playing. A new surface that plays video for the user to watch must route its request through the Viewer's effect, or a shared owner extracted from it, rather than a view attribute.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `viewerKeepsScreenOn`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `ViewerBackdrop`
- `media-kit/src/main/res/layout/player_view_texture.xml`
