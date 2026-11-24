# 0108. Disposable media caches are cleared by their owners, cached bodies that cannot decode are evicted, and downloads stage before publishing

Status: Accepted

## Context

Images, videos, GIFs and Pixiv animations each have a cache with its own owner. Cached images are served without revalidation, and a body that is not an image would fail every later load.

## Decision

- `MediaCacheMaintenance` lets disposable owners clear their own stores; pinned offline media is deliberately separate and never cleared there. `clear` returns false when an active animation kept its archive so callers can offer another clear later.
- `UndecodableImageEviction` drops a request's disk entry on a decode failure that is not a network or HTTP error (an HTML error page sent with 200, a truncated file), so the next load downloads the file again.
- `copyCachedMediaBytes` copies Coil's raw disk entry keyed by the normalized URL that `MediaRequestFactory.imageRequest` loads, without decoding or networking, never writes Coil metadata, and returns false unless an entry of 1 to `maxBytes` exists.
- `ViewerGifByteStore` fills each entry into a `.tmp` staging file first, so prefetch can warm an entry without holding it in memory; `store` publishes only if the cache was not cleared meanwhile. `warmRemoteViewerGif` reuses Coil's raw entry when a feed image already warmed it and bounds the download by `GIF_DOWNLOAD_TIMEOUT_MS`, leaving room for large files on slow links (the connection read timeout catches stalls).

## Consequences

A poisoned cache entry costs one failed load, not a permanently broken image. Offline copies survive any cache clear.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaCacheMaintenance.kt`: `MediaCacheMaintenance`, `clear`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/UndecodableImageEviction.kt`: `UndecodableImageEviction`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaRequestFactory.kt`: `copyCachedMediaBytes`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifBytes.kt`: `ViewerGifByteStore`, `warmRemoteViewerGif`
