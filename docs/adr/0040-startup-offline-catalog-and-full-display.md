# 0040. Startup does not wait for the offline catalog, and the launch reports full display from the restored tab

Status: Accepted

## Context

Until the app container is ready, the app shows "Preparing your library…". Readiness used to include the offline catalog's first scan, which lists every download folder, decodes each manifest and checks each page's length, so startup slowed as downloads grew. On an emulator with 300 downloaded chapters, the scan took most of the time to readiness. Readers of the catalog had relied on it being complete once the app was ready, and an empty catalog reads the same as "no downloads", which would show remote media for posts kept offline or offer to download a Codex already kept offline.

The startup benchmark also measured only time to initial display, the spinner's first frame, because nothing called `reportFullyDrawn`.

## Decision

- `awaitDurableStores` does not await the catalog scan. `OfflineMediaCoordinator` starts it when built.
- `OfflineMediaStore.snapshot` is null until the first scan finishes, so every reader must handle the unscanned state. `load` returns the scanned catalog, waiting for the scan only if none has finished. Once one has, it returns without taking the store's lock, so it never queues behind long store work. `scannedSnapshots` scans first, then follows the catalog. The store's other operations scan under the lock before they act.
- Readers wait for the scan where an empty catalog would be wrong:
  - Every Viewer launch (`launchViewer`) and restore (`restoreSession`) waits for the scan before pages are built, so `requiresResolution` knows which posts have offline copies. If a page is built before the scan anyway, `requiresResolution` treats the post as possibly offline. Resolving it goes through `find`, which waits for the scan, so a path that skips both waits shows the offline copy instead of remote media.
  - `keepOffline` waits before it decides whether an owner keeps copies.
  - Series downloads read through `load` and `scannedSnapshots`.
  - The Offline sheet reads as refreshing, and `codexStates` (with `LocalCodexOfflineStates`) stays null, which hides Codex offline rings and the Codex menu's offline row until the copies are known.
- `recoverPendingJobs` stays on the readiness gate. It reads only the small queue file, and it must register every recovered owner before a screen can start a job whose checkpoint would overwrite that file. Its jobs wait for sources, and their store calls wait for the scan.
- The launch reports full display through `ReportsLaunchContent` in `HomePager`. It holds one `ReportDrawnWhen` lock until the pager rests on the tab restored at startup and that tab has called `ReportLaunchContent`. A single lock at the pager survives the pages composed before the restored tab is shown, which per-page locks would release too early. Each tab reports what it renders, never a hidden state:
  - Search: results, even while slower sources load; the start screen when no search was left; or a settled error or empty result. A search kept from re-running by an edited draft counts as settled.
  - For You: cards, the failure tile, or the end of the deck.
  - Library: Codices, History, or the first Saved shelf that draws. A live shelf counts only once it shows posts or errors, because it reads as not loading before its first fetch. Likes always settles.
  - Series: the shown view's first listing. The decision comes from one `SeriesTabContent` emission.
- The startup benchmarks wait for the system's "Fully drawn" line, allowing `FULLY_DRAWN_TIMEOUT_MS` so a restored search can reach its error state within its request timeout. The benchmark contract tracks `timeToFullDisplayMs` from version v3 on.

## Consequences

Startup no longer grows with downloads. For a moment after launch, the Offline sheet shows its progress bar, and Settings' one-line storage summary can read zero offline bytes. A Viewer opened during the scan waits for it.

A new reader of the offline catalog must handle the null snapshot or call `load`. A new home tab must call `ReportLaunchContent`, or launches restored onto it never report full display.

## Applies to

- `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`: `DefaultTheoriaAppContainer.awaitDurableStores`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/OfflineMediaStore.kt`: `snapshot`, `scannedSnapshots`, `load`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesDownloads.kt`: `states`, `leadPages`, `downloaded`, `deleteRead`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/OfflineMediaCoordinator.kt`: `keepOffline`, `recoverPendingJobs`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaViewerLaunch.kt`: `launchViewer`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRouteWorkflow.kt`: `requiresResolution`, `restoreSession`, `preparePostsForLaunch`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/OfflineStorageViewModel.kt`: `state`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/CodexOfflineRing.kt`: `LocalCodexOfflineStates`
- `app/src/main/java/com/theoriacodex/app/ui/LaunchContent.kt`: `ReportsLaunchContent`, `ReportLaunchContent`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaHomeDestinations.kt`: `HomePager`
- `app/src/main/java/com/theoriacodex/app/ui/routes/SearchRoute.kt`, `ForYouRoute.kt`, `SavedCodexRoute.kt`, `app/src/main/java/com/theoriacodex/app/ui/LibraryDestination.kt`, `app/src/main/java/com/theoriacodex/app/series/SeriesDestinations.kt`: their `ReportLaunchContent` calls
- `macrobenchmark/src/main/java/com/theoriacodex/macrobenchmark/TheoriaMacrobenchmark.kt`: `measureStartup`, `awaitFullyDrawn`
- `scripts/benchmark_results.py`: `default_contract`
