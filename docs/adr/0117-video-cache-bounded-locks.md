# 0117. Background video readers lock the shared cache a chunk at a time, and only plain progressive web files use it

Status: Accepted

## Context

Media3 lets one writer at a time fill a cache range, and a progressive request locks from its position to the next cached span or the end of the file. A feed card or prefetch holding that lock makes the Viewer read around the cache and never cache its own bytes for the stream until a 5 MB fragment fills.

## Decision

- `BoundedLockCache` limits each request of feed cards (behind the Viewer) and neighbour prefetch to `VIDEO_CACHE_CHUNK_BYTES` (2 MiB); `CacheDataSource` commits what it wrote and opens the next request. The Viewer's own player and scrub previews keep unbounded requests: bounding them added request gaps and a preview read-ahead into the player's open chunk that cost later preview frames about 150 ms. Requests never start at the end of a file: a hole is bounded only while more than a chunk is known to remain, or for a file's first request, whose response records the length. The last stretch is unbounded. `RangeRecordingDataSource` records the length from `Content-Range`, or marks the file unbounded when the server ignored ranges (`RANGES_IGNORED_METADATA`).
- `videoCacheDataSourceFactory`: playback bypasses occupied ranges; only background writers may wait for another writer (`blockOnCache`). The swiped-to player reads what a still-running prefetch has committed.
- `usesSharedCache` admits only progressive web files that do not opt out. A manifest never uses the cache: its playlist can change between reads and the cache would answer stale, and its segments are separate files one cache identity cannot name. `VideoPlaybackRequest.cache` is false for full-length episodes, which would evict every short video; such a stream is never prefetched since nothing would read it. `readsAheadFreely` (local file, or shared-cache web file) is the test for preloading, prefetching or scrubbing through a second player without fetching twice; scrub previews also need a single file (`previewsScrubFrames`, ADR 0176).
- `prefetch` warms at most the given bytes from the start of a stream; neighbour videos use `VIEWER_VIDEO_PREFETCH_BYTES` (4 MiB) on every network, so a swipe starts from cache while a never-shown neighbour costs a few megabytes.
- `videoCacheKey` is the same for any header order and name case, differs for different credentials and names neither. Headers are part of the cache identity, so prefetch and playback must send the same ones (the Viewer gets both from `viewerPlayback`). `requestScopedHttp` and `cacheDataSourceFactory` add headers per request, redirects included, and the shared factories never keep them.
- `FeedPreviewPlayerPool.stopForViewer`: an open Viewer hides every feed card, so each preview stops and none holds a decoder, downloads, or the cache range of a stream the Viewer is about to read. Stopped players keep media, position and their last frame.
- `copyFullyCachedVideo` reads the cache only (a cache data source with no upstream fails on any missing byte), so a gap or range held by another writer returns false and never downloads. `sampleFrames` for the tagger reads what the cache holds for free and fetches the rest in bounded ranges without writing to the cache, so sampling never evicts what the user watches. `clearDisposableCache` removes entries through Media3's owner and never deletes its open database or directory.

## Consequences

Viewer playback caches its own bytes while cards preload around it. Raising the chunk size lengthens the window in which another reader is blocked.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/BoundedVideoCache.kt`: `BoundedLockCache`, `RangeRecordingDataSource`, `VIDEO_CACHE_CHUNK_BYTES`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoMediaSources.kt`: `usesSharedCache`, `readsAheadFreely`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoPlaybackInfrastructure.kt`: `VIEWER_VIDEO_PREFETCH_BYTES`, `bind`, `prefetch`, `copyFullyCached`, `sampleFrames`, `clearDisposableCache`, `videoCacheDataSourceFactory`, `copyFullyCachedVideo`, `videoCacheKey`, `requestScopedHttp`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoPlaybackRequest.kt`: `VideoPlaybackRequest.cache`, `VideoPlaybackRequest.headers`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/FeedPreviewPlayerPool.kt`: `FeedPreviewPlayerPool`, `stopForViewer`
