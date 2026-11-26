# 0119. Players are pooled with deferred release, buffer lightly for feed previews, and seek by keyframe while dragging

Status: Accepted

## Context

Many feed previews play simultaneously on one main thread, codecs can run out of resources, and a Viewer drag must feel instant on a link that barely keeps up.

## Decision

- `FeedPreviewPlayerPool` owns preview players beyond individual lazy-grid items. Returning a lease pauses the player immediately; excess idle bindings cool after a grace period (`FEED_PREVIEW_EXCESS_RELEASE_DELAY_MS`) so codecs do not accumulate, and slow player and thread release is paced after the UI path, so disposing a route never releases every visible player one by one synchronously. `ReusableVideoSlotPool` keeps the lease policy separate from Media3 so it is testable and serves the feed and Viewer alike. A card keeps loading until the Viewer stops it. `ViewerVideoPlayerPool` stops media on return and never releases decoders or threads in a page's disposal. Returned players have no media binding; every lease starts at the requested item and sets its repeat mode, because the same player object may come back in the previous caller's mode. A page can leave mid-drag after its player's binding let go, so a lease clears scrubbing mode and pauses.
- `FeedVideoPositionHandoff` is main-thread and one-time so a handoff works whichever order viewer disposal and feed binding happen in.
- Feed previews skip Android media metrics (`usesPlatformDiagnostics = false`): that listener runs on the main thread, nothing on screen uses it, and measured on the phone it cost with many concurrent previews. `playerBuilder` notes a supported hardware codec can still run out during simultaneous autoplay. Speed changes use ExoPlayer's own time stretch, not the AudioTrack's playback params: with those enabled, Media3 1.11.1 leaves a looping video READY but frozen after a seek to 1 s or later (no spinner; the phone recovered after about ten seconds, the emulator never did), which `StreamPlaybackDeviceTest` covers. The cost is that hold-to-speed-up reaches the audio only after the buffered audio drains, so it lags its indicator by a fraction of a second.
- `ViewerLoadControl` is Media3's default buffering except a stream starts, or resumes after a seek, once `VIEWER_START_BUFFER_MS` (500) is buffered, until it stalls; both begin with an empty buffer, so on a fast link this removes half a second. After a stall until the next stream prepares, starts wait for Media3's 1 s and a stall waits for 2 s, so a seek after a stall does not resume on 500 ms and stall again. State is read and written on the player's playback thread, and each player gets its own instance.
- Scrubbing (`VIEWER_SCRUBBING`) snaps each seek to the keyframe at or before the target (a keyframe shows after its own bytes and one decode, where an exact seek downloads and decodes up to the target), with a fractional tolerance of 5% of the duration before the target and none after (`SCRUB_SNAP_FRACTION`). Media3 seeks exactly when no keyframe lies inside that window, so a short clip with one keyframe shows the frame under the finger instead of its first frame, while an episode's keyframes always fall inside it. Guarded by `StreamPlaybackDeviceTest.aHeldInstantSeekInASingleKeyframeClipShowsFramesNearTheTarget`. Subtitles are off during the drag because a side-loaded file is decoded whole after every seek.
- `FirstSeparateAudio` selects the first separate audio track via an override once tracks are read, over Media3's ranking, unless a track was already chosen. An override rather than a preferred label: separate audio has no label of its own, and giving its formats one means wrapping each merged child's period and mapping selections back.
- The HLS source uses Media3's default extractors except they no longer expose an empty CEA-608 track the playlist did not declare (it would otherwise appear on nearly every HLS stream). Each factory is built on first use because a card binds a factory per clip and needs one.
- `MediaTraceSections` names cover every main-thread call into a preview player; `scripts/trace_player_frames.py` groups them, so a new preview section belongs in that script's category table.

## Consequences

Simultaneous autoplay keeps working; release cost stays off interactive paths.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/FeedPreviewPlayerPool.kt`: `FeedPreviewPlayerPool`, `ReusableVideoSlotPool`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayerPool.kt`: `ViewerVideoPlayerPool`, `scrubTo`, `VIEWER_SCRUBBING`, `FirstSeparateAudio`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/FeedVideoPositionHandoff.kt`: `FeedVideoPositionHandoff`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoPlaybackInfrastructure.kt`: `VideoPlaybackProfile`, `ViewerLoadControl`, `VIEWER_START_BUFFER_MS`, `playerBuilder`, `onViewerStreamAcquired`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoMediaSources.kt`: `StreamMediaSourceFactory`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ExoVideoComponents.kt`: `MediaTraceSections`
