# 0112. Pixiv animations play from a validated disk archive that downloads once, retries auth once and never touches offline copies

Status: Accepted

## Context

Pixiv ugoira is a ZIP of frames plus timing metadata from an authenticated API. Several consumers (playback, export, offline save, warm-up) can want the same archive at once, and tokens and rate limits fail intermittently.

## Decision

- Archive downloads are single-flight per post (`archiveWaiters`, 3 concurrent slots). A caller arriving as a flight completed finds its archive in memory, and the archive is cached before the flight completes so no later caller misses both. Clearing and pruning leave archives that waiters or a playback or export consumer use alone (`clearDisposableCache` returns false rather than remove one in use). Each request is cancelled when the last animation consumer leaves.
- Only timing is cached beside the ZIP (`UgoiraCacheIndex`, the `<archive>.zip.json` sidecar): never credentials or Kotlin models, and it counts only while the archive's size and timestamp match. The sidecar is written only after full validation; later reads check structure from the central directory (`checkUgoiraArchiveStructure`) and decoding still enforces per-frame limits. Persistence is optional; a miss re-fetches metadata.
- `withUgoiraAuthRetry` retries I/O errors and 429/5xx with backoff; the first 401/403 refreshes tokens and retries, a second fails (signed out, rate limited or an account mid-change: a later try may pass). Frame entries decode one at a time so a malformed frame is skipped instead of failing playback.
- A cached archive that fails to decode loses its validated sidecar, so the next load revalidates and re-downloads it. Offline archives are user data and are never touched; `copyArchiveForOffline` preserves the validated archive and its timing together for network-free playback. A freshly validated archive that fails to read means damaged data that another download would repeat (`InvalidUgoiraArchive`). `releaseMemoryCaches` drops decoded frames under memory pressure; disk archives stay.
- `decodeUgoiraFrames` publishes immutable frame batches whose bitmaps belong to consumers and are never recycled by the decoder. Display frames decode straight to `Bitmap.Config.HARDWARE` so a new frame each tick needs no RenderThread upload; export reads pixels and stays software; a hardware decode failure (short graphics memory) falls back to software.
- `UgoiraArchiveTooLarge` is final: downloading again gives the same answer.

## Consequences

An animation is downloaded and validated once however many consumers ask. Changing the sidecar format needs a golden for the existing one.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClient.kt`: `PixivUgoiraClient`, `withUgoiraAuthRetry`, `copyArchiveForOffline`, `clearDisposableCache`, `releaseMemoryCaches`, `forgetCachedArchive`, `InvalidUgoiraArchive`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraDiskCache.kt`: `readUgoiraFrameMetadata`, `UgoiraCacheIndex`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraArchive.kt`: `checkUgoiraArchiveStructure`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraFrames.kt`: `decodeUgoiraFrames`, `decodeUgoiraFrame`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraTransport.kt`: `PixivUgoiraTransport`, `UgoiraArchiveTooLarge`
