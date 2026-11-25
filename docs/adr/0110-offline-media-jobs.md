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

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineQueueRecoveryTest.kt`: `restored jobs wait for sources and a save made meanwhile keeps every owner queued (ADR 0110)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineMediaCoordinatorTest.kt`: `clear and owner removal cancel automatic saves still resolving and discard queued posts (ADR 0110)`, `a save while clear waits on a preparation starts no job and leaves no copy (ADR 0110)`, `a save made while clear or a removal runs publishes no job, and a removal leaves other owners alone (ADR 0110)`, `a replaced preparation that already loaded cannot start a job after the removal (ADR 0110)`, `a cancelled preparation that already loaded cannot start a job (ADR 0110)`, `posts saved into a kept owner download alone and other owners are left alone (ADR 0110)`, `posts saved before the first scan finishes wait for it to tell kept owners apart (ADR 0110)`, `an owner with neither copies nor a running job gets nothing when posts are saved (ADR 0110)`, `automatic saves return promptly accumulate during acquisition and drain without loss (ADR 0110)`, `cache on save stores actual bytes independently from disposable caches (ADR 0110)`, `a save stays refused until the last of two removals of its owner ends (ADR 0110)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineMediaAcquisitionTest.kt`: `streaming uses request headers and verifies complete bytes (ADR 0110)`, `partial responses oversized files and provider error pages are rejected (ADR 0110)`, `a file over the byte limit, declared or not, and a partial-content answer are rejected (ADR 0110)`, `a file declared one byte over 2 GiB is refused before anything is written, and one of exactly 2 GiB is not (ADR 0110)`
- `scripts/tests/test_check_architecture.py`: `test_reports_animation_export_through_download_manager_adr_0110`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineMediaCacheCopyTest.kt`: `a copy taken from the media caches is checked like a download (ADR 0110)`
- Left to prose: a user-cancelled request keeping later posts for Retry is not yet guarded (the current test passes without it), testable in media-kit; that every recovered owner is registered before any worker checkpoints is not yet guarded, testable in media-kit with a dispatcher that starts jobs while recovery runs (the current tests' queued dispatcher starts none before it returns); that `OfflineMediaAcquisition` copies cached bytes before checking the network policy is not yet guarded, testable in media-kit; counting only completed galleries as available, publishing only a complete staged file, and saving a ref with no type by its address extension belong to the offline store in `core-data`; the DownloadManager rule reads source text, so a use through reflection would pass it.
