# 0111. Posts get a picture hash from their cached still preview, within a listing budget, so reposts on other sites show once

Status: Accepted

## Context

The same picture appears on several sites and in the Library from before hashes existed. Hashing must not delay a listing, fight the card for bandwidth or collapse a card already on screen.

## Decision

- `PreviewPictureHasher` hashes the still preview a card shows first, whatever the source, loading through the app's image loader with the card's own address, so it fills the disk cache the card then reads. Only a small first-frame bitmap is decoded (`DECODE_PX` 96, enough for a 32x32 average), off the main thread, never into the memory cache and never as an animation (`loadStill`). Coil's interceptor dispatcher is `Dispatchers.Default` because Coil starts requests on the main thread by default and this one has no view.
- `annotate` waits one shared `LISTING_BUDGET_MS` (600 ms) for the page, not per post, since previews download in parallel. Hashes keep running in the hasher's scope after the listing stopped waiting, so the next listing has them at once; a late hash is never applied to the listing that missed it, so a card on screen never collapses or hides because of it. Answers are remembered by preview address for the process (`REMEMBERED`), and flights are started only once stored so removal never runs inside `computeIfAbsent`.
- `loadPreviewStill` is shared with the picture tagger; `pictureHashAt` can be `cachedOnly` to avoid the network.
- `LibraryPictureBackfill` hashes liked and saved posts that have no record yet, run by the charging-only background job, so a repost of an older Library post on another site is recognised. A post saved from a listing already brought its hash; a preview that fails to load is skipped for that run. Such posts keep no record, so each round skips past them in the query (`skip`) rather than reading them again, and a run reads each post at most once: a Library that cannot be reached costs one pass. Each batch's hashes are written in one transaction (`putHashes`), so observers of stored pictures wake once per batch, not once per post.

## Consequences

Duplicates across sites collapse without ever blocking a feed; the cost is that a repost whose preview is slow appears once as a duplicate until the next listing.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PreviewPictureHasher.kt`: `PreviewPictureHasher`, `annotate`, `loadPreviewStill`, `pictureHashAt`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/LibraryPictureBackfill.kt`: `LibraryPictureBackfill`, `drain`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PreviewPictureHasherTest.kt`: `a listing waits for its previews only as long as the budget, and the next listing has the late hashes (ADR 0006, 0111)`, `a listing waits 600 ms by default for its previews (ADR 0111)`, `a preview's answer is remembered by its address, shared while loading, and an unavailable one is tried again (ADR 0111)`, `answers are keyed by preview address, not post, and a load that finishes at once is still remembered (ADR 0111)`, `the still preview is a small first frame decoded off the main thread, outside the memory cache, from the card's address (ADR 0111)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/LibraryPictureBackfillTest.kt`: `the Library is hashed until done, and a preview that fails is skipped (ADR 0111)`, `a Library that cannot be reached is read once, a batch at a time (ADR 0111)`
- Left to prose: that the backfill runs only from the charging-only background job is wiring in `:app` that no test covers; the `REMEMBERED` bound is a size choice with no test.
