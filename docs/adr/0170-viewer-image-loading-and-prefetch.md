# 0170. Viewer prefetch warms the page's own cache entries, keeps only near neighbours decoded, and gates heavy originals

Status: Accepted

## Context

Prefetch must produce the cache entry the page will actually read, without evicting the feed's grid thumbnails or spending megabytes of disk cache on videos and ugoira archives far from the reader, and without starting heavy downloads during fast scrolls or drags.

## Decision

- Prefetch uses the page's own request (`viewerPrefetchImageRequest`), so a decode kept in memory is the hardware bitmap the page shows; with `pageWidthPx` a still is the vertical reader's full-width request. A vertical gallery fills the window's width, which its pages' requests assume (`ViewerImageRenderer` fillWidth), so the prefetch width is the one pages use.
- Order and reach (`planAdjacentViewerPrefetch`): nearest right neighbours first, then left. Images use the full window; videos and ugoira beyond `VIEWER_VIDEO_PREFETCH_DISTANCE` (1) are left to load when shown. Vertical reading looks further ahead (`VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT` = 6) because it moves forward through a column showing several pages.
- Images within `VIEWER_MEMORY_PREFETCH_DISTANCE` (1) are decoded into Coil's memory cache; a page first downloaded while far away is decoded once it becomes a neighbour. Farther images warm the disk cache only, so a full window cannot evict feed grid thumbnails: a far neighbour may reuse an existing memory entry but never adds one (`diskWarmOnly`, `READ_ONLY` memory policy), and a static image decodes subsampled (about `DISK_WARM_DECODE_PX`) since only the download matters.
- A video neighbour is warmed with the request its player starts with, because no height is chosen for it yet; only a web stream that playback reads through the shared cache is warmed there. A Pixiv ugoira warms its archive only off a metered network, otherwise only its still.
- A gallery page's small thumbnail is fetched before its original so the page shows something while it loads (`viewerPrefetchThumbnailLocation`: null when none, not remote, or the page's own image).
- `WarmViewerPageThumbnails` warms all page thumbnails of a multi-page post into disk cache, nearest pages first, so the grid, strip, seek preview and loading pages paint from disk. Leaving the post cancels the warm; turning pages within it does not restart it. Pages whose thumbnail is just their poster are skipped (posters can be heavy) and failed loads are ignored.
- Overview tiles show the thumbnail at once, then the original over it once the tile has stayed on screen for `VIEWER_SHARP_SETTLE_MS` (250 ms), so a fast scroll starts no heavy downloads; the thumbnail is soft at that tile size. A locally saved page keeps using its local file, and a remote AVIF gives way to an equivalent WebP copy when the page lists one, because WebP decodes faster and the overview decodes many tiles together.
- Readiness belongs to one media (`ViewerImageState` is keyed by the media key, retry generation and candidates), so a lazily resolved gallery swaps its cover for page one in place; the painted-image key outlives media swaps so the slot keeps its last image until the next paints.
- `awaitFirstMediaShown` holds speculative loads (prefetch, first comment pages through `beforeFirstFetch`) until the session's first media is ready or failed, or `VIEWER_PREFETCH_READY_WAIT_MS` (1.5 s) passes. Later pages do not wait: someone skimming faster than pages load is better served by neighbours loading ahead. Neighbour GIFs are the exception: they wait for the current media (ADR 0226). `awaitResolutionTurn` makes a neighbour's lookup wait the same way unless its post is, or becomes, the current page. While a series is read, a chapter's cover is not its first media: until the chapter's own pages arrive (`loadingGalleryMedia`) nothing counts as shown, and the wait lasts up to `VIEWER_CHAPTER_READY_WAIT_MS` (`firstMediaWaitMs`), so neighbour chapters' lookups and prefetches wait, while the chapter on screen fetches its first page, before spending a rate-limited extension's quota (Asura Scans: two requests per two seconds). The wait ends when that page shows, when its lookup or image fails, or after `VIEWER_CHAPTER_READY_WAIT_MS`. The chapter's pages take the cover's media key, so replacing a chapter's placeholder bumps each page's load generation (`withLoadGenerationsAfter`); an episode keeps generation 0, which video recovery reads as a first load: a cover callback that lands after the pages arrive no longer counts as page one.
- `reconcileResolutionLookahead` resolves the current page first, then only adjacent posts (next first, then previous). While a series is watched only the episode on screen resolves, plus the next one once the current one passed `ANIME_FINISHED_PERCENT` (`noteWatchedPoint`/`watchedThrough`), so a drag toward a neighbour does not start a lookup. Going on to the next episode then does not wait on a lookup, and its links aging meanwhile are handled by recovery asking for fresh links.
- `requestCurrentPageResolution` resolves the current page once; a recoverable failure retries only when the user returns to the page or through Retry. A provider answering with a different id than requested ends in `ResolutionFailed` (non-recoverable) so the page does not request forever.
- `viewerResolutionTimeoutMs`: a post lookup gets `VIEWER_RESOLUTION_TIMEOUT_MS` (15 s); an episode of a watched series `VIEWER_SERIES_RESOLUTION_TIMEOUT_MS` (60 s) because its extension can take half a minute to find streams. A chapter keeps the post limit.
- `queuePrefetch` runs on the main thread for every selection, so it looks up only the few requested keys. `ViewerPrefetchRequest.pageWidthPx` is set while reading vertically so an image decodes as a full-width page.
- `ViewerPrefetchLedger` keeps prefetch progress outside `ViewerUiState` so progress recomposes nothing: skipped and failed media stay done, a downloaded image is done unless it now wants decoding and is not decoded. Decoding an already downloaded image reads disk and never holds a download slot (`launchPrefetch`). `applyResolvedPost` forgets the post's ledger entry because resolved media may differ from what was warmed.
- `QueuePrefetch` is handled by the ViewModel, not the reducer: the Viewer wants the listed media warm now, most wanted first, and anything else in flight is cancelled.
- A stream the shared cache does not keep (an episode, any HLS or DASH rendition) is not loaded ahead of a swipe (see `ViewerVideoPlayer`); `readsAheadFreely` decides, not the source.

## Consequences

Changing the page's request builder changes what prefetch warms. Raising the memory distance risks evicting feed thumbnails.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPrefetchPlanner.kt`: `planAdjacentViewerPrefetch`, `viewerPrefetchAction`, `VIEWER_VIDEO_PREFETCH_DISTANCE`, `VIEWER_MEMORY_PREFETCH_DISTANCE`, `VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPlatformPrefetcher.kt`: `prefetchViewerMedia`, `viewerPrefetchImageRequest`, `diskWarmOnly`, `warmViewerPageThumbnails`, `prefetchViewerVideoMedia`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaLocations.kt`: `viewerPrefetchThumbnailLocation`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaOverview.kt`: `WarmViewerPageThumbnails`, `ViewerOverviewOriginal`, `overviewStillImageLocation`, `VIEWER_SHARP_SETTLE_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerImageRenderer.kt`: `ViewerImageRenderer`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerNavigationEffects`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerViewModel.kt`: `awaitFirstMediaShown`, `awaitResolutionTurn`, `noteFirstMediaShown`, `firstMediaWaitMs`, `VIEWER_CHAPTER_READY_WAIT_MS`, `reconcileResolutionLookahead`, `mayResolve`, `noteWatchedPoint`, `requestCurrentPageResolution`, `queuePrefetch`, `launchPrefetch`, `ViewerPrefetchLedger`, `applyResolvedPost`, `viewerResolutionTimeoutMs`, `VIEWER_PREFETCH_READY_WAIT_MS`, `VIEWER_RESOLUTION_TIMEOUT_MS`, `VIEWER_SERIES_RESOLUTION_TIMEOUT_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `replaceResolvedPost`, `withLoadGenerationsAfter`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerAction.kt`: `QueuePrefetch`, `ViewerPrefetchRequest`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelTest.kt`: `a series chapter's neighbours wait for its own pages, not its cover (ADR 0170)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelTest.kt`: `a series chapter whose first page never shows frees its neighbours after the chapter wait (ADR 0170)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerViewModelTest.kt`: `only a chapter's pages take a fresh load generation over its cover (ADR 0170)`
