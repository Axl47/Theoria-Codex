# 0169. GIFs render off the main thread through android.graphics.Movie into two alternating bitmaps

Status: Accepted

## Context

GIF scrubbing and speed control need to jump to any time. `Movie` is deprecated, but it is the API here that seeks to an arbitrary time. A `Movie` is not thread-safe, and decoding in the draw pass would block the main thread.

## Decision

- `ViewerGifFrameRenderer` and `ViewerGifPlayer` keep `Movie` (`@file:Suppress("DEPRECATION")`) because it seeks to any time.
- Every Viewer GIF shares one render thread (`gifRenderThread`), so all `setTime`/`draw` calls on a `Movie` are serialized, even when a page and its scrub preview render the same instance. Only the newest requested position is rendered, so scrubbing builds no backlog.
- Two bitmaps alternate (`GIF_RENDER_BUFFERS = 2`). `shown` belongs to the main thread; `spare`, `renderedPosition` and `buffersCreated` belong to the render thread. A bitmap is drawn into only after the main thread has replaced it with a newer frame, so the bitmap on screen is never written. When both buffers are out, `publish()` reschedules the render.

## Consequences

Replacing `Movie` means replacing seek-anywhere support. Any new GIF consumer must go through the shared render thread.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifFrameRenderer.kt`: `ViewerGifFrameRenderer`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifPlayer.kt`: `ViewerGifCanvas`
