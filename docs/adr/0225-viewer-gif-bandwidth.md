# 0225. The Viewer's current GIF gets the bandwidth: neighbour GIFs wait for it, and it downloads in parts

Status: Accepted

## Context

A Viewer GIF shows nothing until its whole file has arrived, because `Movie` (ADR 0169) and `ImageDecoder` both need the complete file. Short GIFs can be large: two Gelbooru GIFs of about five seconds each were 20 MB and 52 MB. One image host's throughput over a single connection swung between about 0.6 and 13 MB/s from run to run, so on a slow run a single connection took up to 89 s, while four parallel ranges of the same file took 5 to 8 s. On the phone, neighbour GIFs prefetched at the same time as the current page shared its bandwidth.

## Decision

- A neighbour GIF's prefetch starts only once the current media is ready or has failed, a failed post lookup included (`launchPrefetch`, `currentMediaSettled`). The same rule ends the session-first-media wait (`noteFirstMediaShown`), so a series chapter's cover never counts as its first page. The hold follows whichever page is current, so skimming past a slow page frees its neighbours as soon as the new page settles. It holds back new starts only: a download already in flight continues. Images, videos and ugoira keep the session-first-media wait of ADR 0170.
- The current page's GIF downloads with `downloadMediaFileInParts`; prefetched neighbours keep one request (`fillRemoteGif`), so parts never multiply connections against a host's rate limit. The first request is a normal GET. If its answer comes over HTTP/1.1 and advertises `Accept-Ranges: bytes` and a `Content-Length` of at least `MIN_PARTED_MEDIA_BYTES` (`mediaParts`), the rest of the file is asked for as `MEDIA_PARTS - 1` ranges in parallel while the first answer's body supplies the first part. Otherwise that answer is read whole, as `downloadMediaFile` does, so a host without ranges costs no extra request. HTTP/2 is left out because its parts would share the first answer's single connection and gain nothing.
- Every range must come back as a 206 whose `Content-Range` is exactly the span asked for, and every part must write exactly its span, so the file is complete when every part returns. Any other answer, or a failed part, falls back to one whole download that overwrites the file.
- Range requests carry the `MediaPartRequest` tag, and `recordingRateLimits` ignores a 429 on them. A host that refuses extra connections then costs one fallback request, instead of marking the whole site rate-limited.
- Each request runs as a blocking call inside the coroutine that owns it (`withResponse`), and cancelling that coroutine cancels the call. The parts are children of the download's scope, so cancelling the page cancels every part, and no part is still writing once the download returns or falls back.
- `ViewerGifByteStore` locks per entry, not per stripe, so the current GIF never waits behind an unrelated neighbour's download.

## Consequences

The current GIF's wait no longer grows with how many neighbour GIFs are queued, and a host that serves ranges is less exposed to one slow connection. Nothing here helps when the network itself is slow: only playing a GIF while it downloads would, which needs a streaming GIF decoder. A host that limits connections per client sees up to `MEDIA_PARTS` at once from the current page.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerViewModel.kt`: `launchPrefetch`, `currentMediaSettled`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaPartsDownload.kt`: `downloadMediaFileInParts`, `mediaParts`, `withResponse`, `MediaPartRequest`, `MEDIA_PARTS`, `MIN_PARTED_MEDIA_BYTES`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/AppHttpClients.kt`: `recordingRateLimits`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/ViewerGifBytes.kt`: `ViewerGifByteStore.withEntry`, `loadRemoteViewerGifBytes`, `warmRemoteViewerGif`, `fillRemoteGif`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelPrefetchTest.kt`: `a neighbor GIF waits until the current media shows or fails, while other neighbors start at once (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a large file from a server that takes ranges arrives in four parallel parts with the page's headers (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a range answered with the whole file, the wrong span, a short body or a 429 falls back to one whole download (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `a small file, a server without ranges or an HTTP 2 host is fetched in one request (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/MediaPartsDownloadTest.kt`: `cancelling the download cancels every part (ADR 0225)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/ViewerGifByteStoreTest.kt`: `every GIF fills under its own lock, so seventeen can download at once (ADR 0225)`
- Left to prose: that neighbour prefetch uses a single request. The guard would be a Robolectric test of `warmRemoteViewerGif` against a ranged fake host.
