# 0006. One file or picture shown once: posts are deduplicated by ID, MD5 and a banded perceptual hash

Status: Accepted

## Context

Mixed-site feeds list the same file on several sites. Resizes, re-encodes and other sites' thumbnails of one image stayed within 4 bits of a 64-bit perceptual hash in the visual-similarity spike, and a booru child post often hashes like its parent.

## Decision

- `SAME_PICTURE_MAX_DISTANCE` is 4 bits. A match means "show once", never "delete".
- `PictureHashIndex` splits the 64 bits into five bands (13, 13, 13, 13, 12), one more than the allowed differing bits, so two hashes within distance 4 agree exactly on at least one band and only entries sharing a band are compared. Not thread-safe.
- `distinctByFile` keeps the first post per ID, per `contentHash` and per picture within the distance, checked in that order (ID, then MD5, then picture). A post is remembered as it passes each check, so one rejected on a later check has still registered its ID, and a post rejected as a picture duplicate has also registered its MD5; a later post with that MD5 is then rejected too. `plusDistinctFiles` keeps already-shown posts even if they copy each other, and appends only incoming posts that are not copies of any shown or earlier incoming post.
- `Post.fileHash` is `contentHash` when reported, else an MD5 found in booru file, sample or thumbnail URLs, so stored and liked posts are recognized without storing a hash. Only the last two non-empty path segments of a URL are searched (file name first, then its folder), for a run of exactly 32 hex digits bounded by non-hex characters. Longer hex runs (SHA-1/SHA-256 names) and query strings do not count, and tokens earlier in the path, such as a signed link's `/get_file/1/<token>/123/123_720p.mp4/`, are skipped; there is no token-specific check, so a 32-hex token inside the last two segments would count.
- `ImageRef` separates the roles of its URLs. `videoVariants` are alternate full-video renditions of one item and never hold previews. `progressiveUrls` are lighter renditions of `url`, lightest first, shown before `url` and then replaced by it. `thumbnailUrl` is a small stand-in for choosing a page and is never shown as the page itself. `fallbackUrls` are equivalent copies of `url` (mirrors, other formats, other shards), tried only when `url` fails and never shown as a lighter step. `fileHash` searches all of them, original first.
- `contentHash` is saved with the post (older posts lack it). `pictureHash` is computed by the app from the still preview, is null when the preview was not hashed in time or is too flat, and is never persisted with the post. `commentHint` and `originUrl` are live listing data, never persisted.
- `keepingListingDataOf` keeps listing-only fields when a resolved or stored post replaces a listed one.
- `galleryLengthUnknown` marks a gallery listing that names neither pages nor count; both arrive with the post's lookup.
- In `Codex.kt`, tags with the same source and `groupIndex` (`CodexAutomaticTag`) are alternatives; separate groups are required.

## Consequences

Cross-site copies collapse without a server-side key. Raising the distance risks hiding distinct pictures; lowering it shows resized copies twice.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/PictureHash.kt`: `SAME_PICTURE_MAX_DISTANCE`, `PictureHashIndex`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`: `Post.contentHash`, `Post.pictureHash`, `Post.fileHash`, `md5InUrlPath`, `ImageRef`, `VideoVariant`, `keepingListingDataOf`, `distinctByFile`, `plusDistinctFiles`, `galleryLengthUnknown`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Codex.kt`: `CodexAutomaticTag.groupIndex`
