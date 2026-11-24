# 0110. Offline copies are made by application-owned jobs admitted through one gate and validated before they count as available

Status: Accepted

## Context

Offline saves must survive navigation, run during startup before sources exist, and never write a copy after a removal or clear has run. Providers sometimes answer a media request with an HTML page.

## Decision

- `OfflineMediaCoordinator` jobs are application-owned. A job is registered and saved to the pending queue at once, then waits for `awaitSources` before resolving its first post, so `recoverPendingJobs` can run during startup without failing posts. Every owner is registered before any worker can checkpoint the recovered intent. Only completed galleries count as available.
- `enqueueOffline` is the one place jobs are admitted. While `remove` runs for an owner or `clear` runs, it admits nothing, so those operations stop every earlier job and no later one can write a copy after they clear. `prepare` runs a loader as an owner's current preparation and admits it only while it is still current and not cancelled, checked under the admission lock, so a list loaded before a newer preparation, cancel, remove or clear can never start a job.
- `keepOffline` adds only the given posts to an owner's copy: a running job takes them at its tail, an owner with copies starts a job for them, a user-cancelled request stays stopped (the posts join what Retry runs), and an owner with neither gets nothing. Saving to a collection admits work immediately (`saveOnCollectionSave`) and feedback never waits on the network.
- `OfflineMediaAcquisition` reuses bytes Coil or the video player already downloaded (`copyFromMediaCaches`, `copyCachedVideo` only for a video cached in full), with no network policy. `downloadMediaFile` streams to a staging file with bounded memory (2 GiB default, caches pass their own limit); callers publish only a complete, recognized file. `validateOfflineMediaFile` rejects a successful HTML or error response so it never becomes an apparently available image. A collection ref that names no type is saved by its address extension and reports no type.
- `OfflineAwareCacheRepository` never treats remote URL pointer files as downloads: full-media caching must acquire bytes.
- Ugoira export uses the application transport rather than Android's queued DownloadManager (`animationExportNetworkBlock`).

## Consequences

Removal and clear are race-free with respect to running jobs. Recovery cannot fail posts just because startup is early.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/OfflineMediaCoordinator.kt`: `OfflineMediaCoordinator`, `prepare`, `keepOffline`, `enqueueOffline`, `saveOnCollectionSave`, `OfflineAwareCacheRepository`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/OfflineMediaAcquisition.kt`: `OfflineMediaAcquisition`, `copyFromMediaCaches`, `downloadMediaFile`, `validateOfflineMediaFile`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaNetworkPolicy.kt`: `animationExportNetworkBlock`
