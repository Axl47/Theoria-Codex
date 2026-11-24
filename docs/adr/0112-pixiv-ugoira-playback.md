# 0112. Pixiv animations play from a validated disk archive that downloads once, retries auth once and never touches offline copies

Status: Accepted

## Context

Pixiv ugoira is a ZIP of frames plus timing metadata from an authenticated API. Several consumers (playback, export, offline save, warm-up) can want the same archive at once, and tokens and rate limits fail intermittently.

## Decision

- Archive downloads are single-flight per post (`archiveWaiters`, 3 concurrent slots). A caller arriving as a flight completed finds its archive in memory, and the archive is cached before the flight completes so no later caller misses both. Clearing and pruning leave archives that waiters or a playback or export consumer use alone (`clearDisposableCache` returns false rather than remove one in use). Each request is cancelled when the last animation consumer leaves.
- Only timing is cached beside the ZIP (`UgoiraCacheIndex`, the `<archive>.zip.json` sidecar): never credentials or Kotlin models, and it counts only while the archive's size and timestamp match. The sidecar is written only after full validation; later reads check structure from the central directory (`checkUgoiraArchiveStructure`) and decoding still enforces per-frame limits. Persistence is optional; a miss re-fetches metadata.
- `withUgoiraAuthRetry` retries I/O errors and 429/5xx with backoff; the first 401/403 refreshes tokens and retries, a second fails (signed out, rate limited or an account mid-change: a later try may pass). Frame entries decode one at a time so a malformed frame is skipped instead of failing playback.
- A cached archive that fails to decode loses its validated sidecar, so the next load validates the cached ZIP again and downloads it again only if that validation fails. Offline archives are user data and are never touched; `copyArchiveForOffline` preserves the validated archive and its timing together for network-free playback. A freshly validated archive that fails to read means damaged data that another download would repeat (`InvalidUgoiraArchive`). `releaseMemoryCaches` drops decoded frames under memory pressure; disk archives stay.
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

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClientTest.kt`: `concurrent consumers share one metadata and archive acquisition (ADR 0112)`, `partial frames reach both consumers and cancelling one preserves the other (ADR 0112)`, `last departing consumer interrupts archive acquisition and a returning card retries (ADR 0112)`, `no more than three archives download at once (ADR 0112)`, `clearing the disposable cache while an archive downloads keeps it and reports false (ADR 0112)`, `clearing the disposable cache while playback decodes keeps the archive and reports false (ADR 0112)`, `pruning the archive store leaves an archive still being fetched (ADR 0112)`, `an archive that fails validation leaves neither the archive nor its timing (ADR 0112)`, `transient metadata failures stop after three attempts (ADR 0112)`, `metadata and archive requests retry an I-O failure, 429 and 5xx with growing waits, then stop after three tries (ADR 0112)`, `the first 401 or 403 refreshes the token and retries with it, and a second one fails (ADR 0112)`, `a malformed frame entry is skipped and the rest still play (ADR 0112)`, `a cached archive that fails to decode loses its timing, so the next load validates it again (ADR 0112)`, `a cached archive that no longer validates is downloaded again (ADR 0112)`, `an offline archive that fails to decode keeps its timing (ADR 0112)`, `memory pressure drops decoded frames and playback recovers from the disk archive (ADR 0112)`, `deadline terminates partial decoding without caching or recycling displayed frames (ADR 0112)`, `an archive over the compressed limit is not downloaded again (ADR 0112)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraFramesTest.kt`: `disk metadata rejects damaged or replaced cache pairs (ADR 0112)`, `disk metadata the Gson writer stored still matches its archive (ADR 0112)`, `the timing sidecar stores only frame timing and the archive's size and time (ADR 0112)`, `decoder publishes the first frame immediately and reuses it for complete playback (ADR 0112)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraDiskCacheGoldenTest.kt`: `the ugoira sidecar older builds wrote decodes the same (ADR 0112)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraArchiveTest.kt`: `valid archive reports bounded compressed expanded and frame counts (ADR 0112)`, `archive rejects unsafe names metadata mismatch and duplicate frame specs (ADR 0112)`, `archive rejects compressed frame-count and per-frame expansion limits (ADR 0112)`
- Left to prose: an export or offline copy holding its archive against clearing has no test (the copy runs inside one blocking call no test can hold open), and the archive being cached before its flight completes has no test; that display frames decode to hardware bitmaps with a software fallback while exports stay software needs real graphics memory, so only a device shows it.
