# 0120. Viewer images decode to hardware bitmaps, and tall pages are bounded by what the GPU can draw

Status: Accepted

## Context

Uploading a full-size page to the GPU on RenderThread as it settles causes jank, a hardware canvas refuses to draw bitmaps over about 100 MB, and the vertical reader lays pages out before their shape is known.

## Decision

- `buildViewerImageRequest` has no crossfade (the preview leaves as the image arrives, so fading would dip through the background) and decodes to hardware bitmaps; callers that read pixels (the artwork accent) opt back into software with `allowHardware(false)`.
- `buildViewerPageRequest` decodes a page for the full `pageWidthPx` the vertical reader lays it out at, whatever its shape, and never upscales. Sizing from the layout slot would shrink a tall strip to a sliver; the reader's prefetch uses the same request and width so the bitmap it keeps in memory is the one the page shows. Nothing outside the vertical reader should size this way.
- `viewerPageDecodeBounds` fits the page into the viewport width and as tall as the GPU's max edge and `MAX_PAGE_BITMAP_BYTES` (64 MiB, under the 100 MB drawing limit with headroom) allow; a strip too tall for both is scaled down. The max edge is queried once through a throwaway 1x1 offscreen OpenGL ES context on a background thread that holds no context of its own, inside Coil's pipeline so it never runs on the UI thread; the default display is shared with HWUI and never terminated. It falls back to 4096, Vulkan's guaranteed minimum.

## Consequences

Pages never crash the canvas; very tall strips lose resolution until they decode as tiles.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerImageRequest.kt`: `buildViewerImageRequest`, `buildViewerPageRequest`, `ViewerPageSizeResolver`, `viewerPageDecodeBounds`, `maxBitmapEdgePx`, `queryMaxTextureSize`
