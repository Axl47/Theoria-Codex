# 0017. Visual signatures and For You verdicts are stored with their own lifecycle

Status: Accepted

## Context

Visual similarity stores perceptual hashes, tagger output and For You swipes. Skipped posts are never stored, and signature rows can carry large embeddings.

## Decision

- `ForYouVerdictEntity` has no foreign key to posts: skipped posts are not stored, so the row keeps the preview address to show or hash the picture later. A swipe landing after its profile was deleted is refused by the repository's write guard.
- `VisualSignatureEntity` rows follow their post (deleted with it). A null `phash` with `phash_checked` means the picture was looked at and cannot be hashed, so it is not fetched again; without `phash_checked` the row came from the tagger and no hash was tried. `frame_state` is 0 (still only), 1 (still of a post that plays, frames to read) or 2 (still plus frames); `frame_attempts` counts failed frame reads and at the cap the still analysis stands.
- `observeHashed` selects only the hash columns, since listing hashes must not load embeddings.

## Consequences

Hash and tagger passes can run independently and resume. Adding a frame state or cap changes both the entity and the progress queries.

## Applies to

- `core-data-android/src/main/java/com/theoriacodex/data/android/room/ForYouVerdictEntity.java`: `ForYouVerdictEntity`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomForYouVerdictRepository.kt`: `RoomForYouVerdictRepository`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/VisualSignatureEntity.java`: `VisualSignatureEntity`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/VisualSignatureDao.java`: `observeHashed`
