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

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaDurationCoordinatorTest.kt`: `same fingerprint shares one job and one remaining consumer keeps it alive (ADR 0104)`, `last consumer release cancels active work and removes pending state (ADR 0104)`, `terminal state and player publication do not restart acquisition (ADR 0104)`, `an answer that needs no work never queues (ADR 0104)`, `stored no-media verdict is re-derived after a source gains a resolver (ADR 0104)`, `a failed lookup is not retried by new demand until the retry time (ADR 0104)`, `a duration that cannot be read or stored is still published (ADR 0104)`, `pixiv lookups run two at a time while another source is not held to two (ADR 0104)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaDurationRouteViewModelTest.kt`: `active filter resolves loaded posts without rewriting them (ADR 0104)`, `a feed-supplied duration answers from the post without lookup or storage (ADR 0104)`, `nearby posts resolve with background resolution off and others stay idle (ADR 0104)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/DurationRequestPacerTest.kt`: `a source starts one lookup per interval (ADR 0104)`, `lookups already waiting honour a cooldown that starts later (ADR 0104)`, `a cooldown on one source leaves the others running (ADR 0104)`, `pixiv spaces lookups half a second apart, custom sites are paced like gelbooru and CDN sources do not wait (ADR 0104)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaDurationAcquisitionEngineTest.kt`: `time spent waiting for the pacer does not count against the lookup timeout (ADR 0104)`, `overall timeout cancels probe and becomes a bounded retry (ADR 0104)`, `a throttled lookup pauses its source and retries before giving up (ADR 0104)`, `media that needs no lookup settles without work (ADR 0104)`, `the last throttled try still pauses its source (ADR 0104)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/BoundedMediaDurationProbeTest.kt`: `metadata in the first bytes needs only the small window (ADR 0104)`, `metadata past the media data is read directly from its box offset (ADR 0104)`, `metadata the small window cannot reach falls back to the full window (ADR 0104)`, `gelbooru answering 404 reads as throttling while other sources stay a transport failure (ADR 0104)`, `metadata behind two boxes is reached one box at a time (ADR 0104)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaDurationProbeTest.kt`: `duration probe leaves an episode's streams to its player (ADR 0104)`
- Left to prose: that routes never copy durations into post lists and never drain acquisition loops follows from what `MediaDurationRouteViewModel` exposes (a state map, not posts) rather than from a test; the head-and-tail fallback for containers other than MP4 has no fixture; `DurationLookupPolicy` defaults to unpaced, so a new rate-limited source is paced only if its author states a policy (making the parameter required touches every catalog entry); `observedMediaDurationMs` sparing the feed a recomposition is a performance intent; the retry delay and window sizes are tuning values.
