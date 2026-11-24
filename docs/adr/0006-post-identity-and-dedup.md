# 0006. One file or picture shown once: posts are deduplicated by ID, MD5 and a banded perceptual hash

Status: Accepted

## Context

Mixed-site feeds list the same file on several sites. Resizes, re-encodes and other sites' thumbnails of one image stayed within 4 bits of a 64-bit perceptual hash in the visual-similarity spike, and a booru child post often hashes like its parent.

## Decision

- `SAME_PICTURE_MAX_DISTANCE` is 4 bits. A match means "show once", never "delete".
- `PictureHashIndex` splits the 64 bits into five bands (13, 13, 13, 13, 12), one more than the allowed differing bits, so two hashes within distance 4 agree exactly on at least one band and only entries sharing a band are compared. Not thread-safe.
- `distinctByFile` keeps the first post per ID, per `contentHash` and per picture within the distance. `plusDistinctFiles` keeps already-shown posts even if they copy each other, and appends only incoming posts that are not copies of any shown or earlier incoming post.
- `Post.fileHash` is `contentHash` when reported, else an MD5 found in booru file, sample or thumbnail URLs, so stored and liked posts are recognized without storing a hash. Only exactly 32 hex digits in the file name or its folder count (not SHA-1/SHA-256 names, query strings, or signed-link tokens).
- `contentHash` is saved with the post (older posts lack it). `pictureHash` is computed by the app from the still preview, is null when the preview was not hashed in time or is too flat, and is never persisted with the post. `commentHint` and `originUrl` are live listing data, never persisted.
- `keepingListingDataOf` keeps listing-only fields when a resolved or stored post replaces a listed one.
- `galleryLengthUnknown` marks a gallery listing that names neither pages nor count; both arrive with the post's lookup.
- In `Codex.kt`, tags with the same source and `groupIndex` (`CodexAutomaticTag`) are alternatives; separate groups are required.

## Consequences

Cross-site copies collapse without a server-side key. Raising the distance risks hiding distinct pictures; lowering it shows resized copies twice.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/PictureHash.kt`: `SAME_PICTURE_MAX_DISTANCE`, `PictureHashIndex`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`: `Post.contentHash`, `Post.pictureHash`, `Post.fileHash`, `md5InUrlPath`, `keepingListingDataOf`, `distinctByFile`, `plusDistinctFiles`, `galleryLengthUnknown`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Codex.kt`: `CodexAutomaticTag.groupIndex`
