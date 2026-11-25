# 0108. Disposable media caches are cleared by their owners, cached bodies that cannot decode are evicted, and downloads stage before publishing

Status: Accepted

## Context

Images, videos, GIFs and Pixiv animations each have a cache with its own owner. Cached images are served without revalidation, and a body that is not an image would fail every later load.

## Decision

- `MediaCacheMaintenance` lets disposable owners clear their own stores; pinned offline media is deliberately separate and never cleared there. `clear` returns false when an active animation kept its archive so callers can offer another clear later.
- `UndecodableImageEviction` drops a request's disk entry when the load fails with an exception that is neither an `IOException` nor an HTTP error (such as an HTML error page sent with 200), so the next load downloads the file again; a failure reported as an `IOException` keeps the entry, as a network failure does.
- `copyCachedMediaBytes` copies Coil's raw disk entry keyed by the normalized URL that `MediaRequestFactory.imageRequest` loads, without decoding or networking, never writes Coil metadata, and returns false unless an entry of 1 to `maxBytes` exists.
- `ViewerGifByteStore` fills each entry into a `.tmp` staging file first, so prefetch can warm an entry without holding it in memory; `store` publishes only if the cache was not cleared meanwhile. `warmRemoteViewerGif` reuses Coil's raw entry when a feed image already warmed it and bounds the download by `GIF_DOWNLOAD_TIMEOUT_MS`, leaving room for large files on slow links (the connection read timeout catches stalls).

## Consequences

A poisoned cache entry costs one failed load, not a permanently broken image. Offline copies survive any cache clear.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaCacheMaintenance.kt`: `MediaCacheMaintenance`, `clear`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/UndecodableImageEviction.kt`: `UndecodableImageEviction`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaRequestFactory.kt`: `copyCachedMediaBytes`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifBytes.kt`: `ViewerGifByteStore`, `warmRemoteViewerGif`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/media/UndecodableImageEvictionTest.kt`: `a cached body that fails to decode is dropped while network and HTTP failures keep the cache (ADR 0108)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaRequestFactoryTest.kt`: `cached bytes are copied from the entry keyed by the normalized url, only within the size bounds (ADR 0108)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/ViewerGifByteStoreTest.kt`: `a fill is staged in a temporary file and a clear during it publishes nothing (ADR 0108)`
- Left to prose: that `MediaCacheMaintenance` never clears offline copies holds because it has no reference to them, and its `clear` result passing on the Pixiv archive's answer has no host test (it reaches the context's video and image singletons); that `copyCachedMediaBytes` never writes Coil metadata, that `warmRemoteViewerGif` reuses Coil's entry, and the length of `GIF_DOWNLOAD_TIMEOUT_MS` are not guarded; not yet guarded, testable in media-kit once the timeout can be injected: that a viewer GIF download stops at that bound (the fill runs on the IO dispatcher, so only a two-minute real-time test could show it today).
