# 0225. The Viewer's current GIF gets the bandwidth: neighbour GIFs wait for it, and it downloads in parts

Status: Accepted

## Context

A Viewer GIF shows nothing until its whole file has arrived, because `Movie` (ADR 0169) and `ImageDecoder` both need the complete file. Short GIFs can be large: two Gelbooru GIFs of about five seconds each were 20 MB and 52 MB. One image host's throughput over a single connection swung between about 0.6 and 13 MB/s from run to run, so on a slow run a single connection took up to 89 s, while four parallel ranges of the same file took 5 to 8 s. On the phone, neighbour GIFs prefetched at the same time as the current page shared its bandwidth.

## Decision

- A neighbour GIF's prefetch starts only once the current media is ready or has failed, a failed post lookup included (`launchPrefetch`, `currentMediaSettled`). It checks again once it holds a download slot and gives the slot back if the page that is now current is still loading (`withPermitWhileCurrentSettled`). The same rule ends the session-first-media wait (`noteFirstMediaShown`), so a series chapter's cover never counts as its first page, and a GIF neighbour does not also wait for that. The hold follows whichever page is current, so skimming past a slow page frees its neighbours as soon as the new page settles. It holds back new starts only: a download already in flight continues, even when its page becomes current, and that page's load then waits for it (`runGifPrefetch`). Two cases would otherwise hold neighbours for as long as the user stays on a page: a page that finished loading while the user was still swiping to it now counts as ready once it becomes current (`reduceMediaReady`), and a GIF download that runs out of time fails the load instead of leaving it spinning (`fillRemoteGif`). Other ways a page can stay neither ready nor failed remain (a load that fails during the swipe onto it, a post lookup that failed before the page was selected), so no neighbour GIF waits longer than `VIEWER_GIF_HOLD_MAX_MS`. Images, videos and ugoira keep the session-first-media wait of ADR 0170.
- The current page's GIF downloads with `downloadMediaFileInParts`; prefetched neighbours keep one request (`fillRemoteGif`), and so does a page only swiping into view: `ViewerGifPlayer` decides from `controls.isActive` when each load attempt starts (`loadRemoteViewerGifBytes`'s `current`). Parts never multiply connections against a host's rate limit. The first request is a normal GET. If its answer comes over HTTP/1.1 and advertises `Accept-Ranges: bytes` and a `Content-Length` of at least `MIN_PARTED_MEDIA_BYTES` (`mediaParts`), the rest of the file is asked for as `MEDIA_PARTS - 1` ranges in parallel while the first answer's body supplies the first part, after which that first connection is closed. Otherwise that answer is read whole, as `downloadMediaFile` does, so a host without ranges costs no extra request. HTTP/2 is left out because its parts would share the first answer's single connection and gain nothing.
- Ranges carry `If-Range` with the first answer's strong ETag, or its Last-Modified; an answer with neither is read whole, since a change to the file could not be detected. Every range must come back as a 206 whose `Content-Range` and `Content-Length` are exactly the span asked for and whose ETag, when the first answer had one, matches it, and every part must write exactly its span, so the file is complete when every part returns. Any other answer, or a failed part, falls back to one whole download that overwrites the file, so two versions of a file are never stitched together.
- Range requests are marked `withoutRateLimitRecording`, and `recordingRateLimits` ignores a 429 on them. A host that refuses extra connections then costs one fallback request, instead of marking the whole site rate-limited.
- Each request, the single-request path's included, runs as a blocking call inside the coroutine that owns it (`withResponse`), and cancelling that coroutine cancels the call. The parts are children of the download's scope, so cancelling the page cancels every part, and nothing is still writing once a download returns, falls back or is cancelled.
- `ViewerGifByteStore` locks per entry, not per stripe, so the current GIF never waits behind an unrelated neighbour's download.

## Consequences

The current GIF's wait no longer grows with how many neighbour GIFs are queued, and a host that serves ranges is less exposed to one slow connection. Nothing here helps when the network itself is slow: only playing a GIF while it downloads would, which needs a streaming GIF decoder. A host that limits connections per client sees up to `MEDIA_PARTS` at once from the current page.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerViewModel.kt`: `launchPrefetch`, `withPermitWhileCurrentSettled`, `runGifPrefetch`, `queuePrefetch`, `currentMediaSettled`, `VIEWER_GIF_HOLD_MAX_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `reduceMediaReady`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaPartsDownload.kt`: `downloadMediaFileInParts`, `mediaParts`, `MEDIA_PARTS`, `MIN_PARTED_MEDIA_BYTES`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/OfflineMediaAcquisition.kt`: `downloadMediaFile`, `withResponse`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/AppHttpClients.kt`: `recordingRateLimits`, `withoutRateLimitRecording`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifPlayer.kt`: `ViewerGifLoadEffect`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifBytes.kt`: `ViewerGifByteStore.withEntry`, `loadRemoteViewerGifBytes`, `warmRemoteViewerGif`, `fillRemoteGif`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelPrefetchTest.kt`: `a neighbor GIF waits until the current media shows or fails, while other neighbors start at once (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a large file from a server that takes ranges arrives in four parallel parts with the page's headers (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a range answered with the whole file, the wrong span or size, another version or a 429 falls back to one whole download (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a small file, a server without ranges or a version to check, or an HTTP 2 host is fetched in one request (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `cancelling the download cancels every part (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/ViewerGifByteStoreTest.kt`: `every GIF fills under its own lock, so seventeen can download at once (ADR 0225)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelPrefetchTest.kt`: `a neighbor GIF that waited for a download slot still waits while the page now current loads (ADR 0225)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelPrefetchTest.kt`: `a neighbor GIF already downloading keeps going when its page becomes current (ADR 0225)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelPrefetchTest.kt`: `a neighbor GIF stops waiting once the hold limit passes, even if the current page never settles (ADR 0225)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/state/ViewerStateBoundaryTest.kt`: `a page that showed while the user was swiping to it counts as shown once it becomes current (ADR 0225)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerGifCurrentPageTest.kt`: `only the current GIF page asks for its file in parts, not one swiping into view (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/ViewerGifTransportTest.kt`: `warming a neighbor GIF asks for the whole file in one request, even from a host that takes ranges (ADR 0225)`
