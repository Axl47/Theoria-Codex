# 0104. Video lengths are looked up by one application-scoped coordinator, paced per source, and cached without changing outcomes

Status: Accepted

## Context

Feeds can filter by length, but many posts carry none. A lookup reads from a media host that also serves the videos on screen, so tripping its limit would break playback. Routes come and go as the user navigates.

## Decision

- `MediaDurationCoordinator` is application-scoped and owns demand, cancellation, single-flight work and publication. Routes never drain acquisition loops, and publishing metadata never becomes a new demand input; `MediaDurationRouteViewModel` is a navigation-scoped demand adapter that never copies durations into route post lists.
- A duration the feed supplies is read from the post itself: nothing is looked up or kept. Answers that need no network (`settleWithoutWork`) skip the queue so they never wait behind paced lookups.
- `DurationLookupPolicy` (per source in `SourcePresentationCatalog`) spaces lookup starts and caps concurrency, because lookups share a host and IP with video playback. Pixiv lookups are an API call (500 ms, 2 at a time); other sources are ranged reads from a CDN; custom sites are paced like Gelbooru since their limits are unknown. `notFoundMeansThrottled` marks a host that answers an overloaded client with 404. `DurationRequestPacer` suspends until a source may start and holds later lookups after a throttle.
- The pacing wait sits outside the lookup timeout, which bounds only time on the network. A failed lookup waits `DEFAULT_DURATION_RETRY_DELAY_MS` before new demand may retry it.
- A stored "no authoritative media" answer is not trusted: it is cheap to rederive and a source may have gained a resolver. Persistence is a cache optimization and never changes a successful outcome.
- `BoundedMediaDurationProbe` settles most media from a small first read, follows MP4 boxes to `moov`, and otherwise falls back to head and tail windows. Time is bounded by the engine, not the probe.
- `authoritativeDurationProbeRef` never probes a series episode's streams: its player publishes the length, and a probe would be a second request against an expiring link with the source's headers instead of the stream's own.
- The Viewer's current post and neighbours resolve regardless of background resolution, so a timeline can show its length before the player has it. `observedMediaDurationMs` collects one post key so a badge update does not invalidate the whole feed.

## Consequences

Length filtering cannot starve playback of a host's request budget. Adding a source needs a pacing policy if its host is rate limited.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationCoordinator.kt`: `MediaDurationCoordinator`, `DEFAULT_DURATION_RETRY_DELAY_MS`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationAcquisitionEngine.kt`: `settleWithoutWork`, `acquire`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationRouteViewModel.kt`: `MediaDurationRouteViewModel`, `onNearbyPostsChanged`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationCardState.kt`: `observedMediaDurationMs`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/DurationRequestPacer.kt`: `DurationRequestPacer`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/BoundedMediaDurationProbe.kt`: `BoundedMediaDurationProbe`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaDurationProbe.kt`: `authoritativeDurationProbeRef`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceMetadata.kt`: `DurationLookupPolicy`, `SourcePresentationCatalog`
