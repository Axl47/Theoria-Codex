# 0183. The image loader keeps a large immutable disk cache and big models are released when the process is at risk

Status: Accepted

## Context

Coil's defaults sized the disk cache at 2% of disk (at most 250 MB), so a few Viewer originals evicted the grid's thumbnails. Revalidating cached images cost a round trip each on origins that send only validators (nginx's default), including every Viewer page a prefetch had already downloaded. The picture tagger holds about 350 MB while loaded.

## Decision

- `TheoriaApplication.newImageLoader` uses the same directory as Coil's default (`image_cache`), so the existing cache survives, with an explicit size of `IMAGE_DISK_CACHE_MAX_BYTES` (512 MB).
- It sets `respectCacheHeaders(false)`: media URLs name files that never change, so a cached copy is used as is. Responses sent with `no-store` were never cached.
- `onTrimMemory` at `TRIM_MEMORY_BACKGROUND` or above, and `onLowMemory`, release what the app owns (the Pixiv ugoira client's caches and the picture tagger, which loads again for the next picture). Coil trims its own memory cache.

## Consequences

Thumbnails and Viewer originals coexist on disk and cached images are never revalidated. A change to a media file must therefore change its URL.

## Applies to

- `app/src/main/java/com/theoriacodex/app/TheoriaApplication.kt`: `newImageLoader`, `IMAGE_DISK_CACHE_MAX_BYTES`, `onTrimMemory`, `onLowMemory`, `releaseMemoryCaches`
