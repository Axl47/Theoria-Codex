# 0130. Animated media share one clock, one timeline and a hold-to-scrub gesture without recomposing per frame

Status: Accepted

## Context

Video, GIF and ugoira share a timeline; frames and positions change every frame, and preloaded pages must not report or play as if current.

## Decision

- Playback time is local: pausing never counts time off screen; playback holds at the decoded boundary and a seek beyond waits; readiness is reported again when preloaded media becomes active.
- Positions and drag state are read while drawing (`positionMs` lambdas, `scrubbing`), so frames and drags redraw without recomposing. Only the image and timeline consume frame changes; `ViewerRendererControls` carries route commands only, never frame positions or decoder state. Cards and off-screen pages draw the bare frame.
- Hold-to-speed-up drags scrub the timeline through `ViewerHoldScrub`: the flag is read once when a gesture locks direction; each end is a new instance so a stale `Ended` from an earlier gesture is ignored; a release lands on the last offset even if no `Dragging` state was seen (snapshot state conflates); abandoned holds cancel. A drag lands where the finger last moved. An unmeasured timeline begins on the next move.
- Duplicate double-tap seek jumps are applied once by serial and dropped while disabled.
- The timeline row steps aside while a preview shows the time; track and row heights stay inside the footer height gestures reserve; the scrim lives inside the player so it does not dim the timeline; time readouts use tabular figures.
- Post dimensions describe only the first page; a gallery cover is never shown as a different page.

## Consequences

New renderers reuse the timeline and must keep frame state out of route state.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/AnimationPlayback.kt`: `LoopingAnimationClock`, `AnimationPlaybackState`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/AnimatedFramesPlayer.kt`: `rememberAnimatedFrames`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/MediaTimelineBar.kt`: `MediaTimelineBar`, `HoldScrubFollower`, `timelineScrubGestures`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerHoldScrub.kt`: `ViewerHoldScrub`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSeekJumps.kt`: `ViewerSeekJumps`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRendererControls.kt`: `ViewerRendererControls`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVeil.kt`: `ViewerTimelineVeil`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerLoadingPreview.kt`: `viewerMediaAspectRatio`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraPlayerUi.kt`: `UgoiraLoadedPlayer`
