# 0176. Scrub previews come from a paused, video-only player that seeks to keyframes and stops between drags

Status: Accepted

## Context

Dragging a video timeline shows the frame at the nearest keyframe. Media3's `FrameExtractor` had to be rebuilt per drag, its one process-wide player refuses a request while a cancelled one still decodes, and it always prepares and decodes 0:00 before its first seek, about 340 ms before a drag's first request. A paused request also keeps filling socket buffers (about 2 MB) on the link the next seek needs.

## Decision

- `ScrubPreviewPlayer` replaces `FrameExtractor`: it seeks as soon as the stream's index is read and keeps its decoder and GL pipeline across drags. Its renderer, software decoder, HDR and rotation handling and pixel read follow `FrameExtractor`'s (HDR input is read as SDR and rotation is applied as an effect, because frames never reach a display surface).
- A target waits in `ScrubFrameMatcher` until the stream's index is read, since only then do seeks resolve to keyframes. A late timeline from a stopped stream is ignored. A target that resolves to the frame already shown is answered with that frame, because the player renders nothing for a seek to its current position. `ScrubPreviewPlayer` (as a `ScrubFrameSource`), the matcher and `ViewerScrubPreviews` are all used on the main thread.
- Between drags `idle()` stops the player (no request stays open) while foreground mode keeps the decoder. The next `frameAt` sets the media item again and prepares it, which re-reads the index and signals when seeks resolve to keyframes again.
- `previewLoadControl` buffers 1 s: a keyframe needs little more than itself, and a second covers decoders that hold frames for reordering.
- The GL read returns rows bottom-up, so a `MatrixTransformation` flips the frame vertically.
- `PreviewFrameRenderer` uses Media3's library-internal `PlaybackVideoGraphWrapper` (`@SuppressLint("RestrictedApi")`), the same setup `FrameExtractor` uses to render decoded frames into an effect chain. The pinned Media3 version keeps it stable, and a changed signature fails compilation on upgrade.
- `rememberViewerScrubPreviews` extracts at the size the popup actually shows (`viewerScrubFrameSize` in pixels, rounded down to even width and height with `Int.even()`, since video encoders and effects expect even dimensions).
- `ViewerScrubPreviews` keeps one frame source (a `ScrubPreviewPlayer`) per stream location, built on the first drag and kept idle between drags until `close`, or until a drag fails or times out (`SCRUB_FRAME_TIMEOUT_MS`); the next drag then starts afresh. Frame requests drop subtitles and separate audio tracks.
- `followScrubTargets` handles targets one at a time, so one frame is pending at a time, and only the newest target may publish. Separately, a direct `ScrubFrameSource.frameAt` cancels any request still pending on that source. The first target (where the finger landed) is extracted at once; later ones only after the finger rests on them for `SCRUB_FRAME_SETTLE_MS` (80 ms).
- `seekReleased` idles the preview before seeking, because until its request closes what it read is uncommitted and its cache lock makes the player download those bytes again. When the last published frame is the release target's, playback seeks to that frame's keyframe using `SeekParameters.CLOSEST_SYNC` (the keyframe time is truncated to milliseconds, so an exact seek there would decode from the keyframe before it); otherwise the seek is exact. Seek parameters are restored afterwards.
- `ViewerVideoState.release`: after an instant-seek drag (`scrubTo`) the release seek is exact and reads on from the keyframe the drag showed; otherwise the previews may land it on the frame their popup shows.
- Previews are created only when `playback.readsAheadFreely()` holds and only while the timeline is shown. They read through the shared cache so a release plays from bytes they fetched; a stream the cache does not keep would fetch every frame twice.

## Consequences

The first request of a drag no longer pays the 0:00 prepare. Upgrading Media3 may break the renderer at compile time; that is intended.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ScrubPreviewPlayer.kt`: `ScrubPreviewPlayer`, `PreviewFrameRenderer`, `previewLoadControl`, `FrameGrabber`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ScrubFrameMatcher.kt`: `ScrubFrameMatcher`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScrubPreview.kt`: `rememberViewerScrubPreviews`, `ViewerScrubPreviews`, `followScrubTargets`, `SCRUB_FRAME_SETTLE_MS`, `SCRUB_FRAME_TIMEOUT_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayer.kt`: `ViewerVideoState.release`, the `rememberViewerScrubPreviews` call guarded by `readsAheadFreely`
