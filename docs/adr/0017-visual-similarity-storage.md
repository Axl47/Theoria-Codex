# 0017. Visual signatures and For You verdicts are stored with their own lifecycle

Status: Accepted

## Context

Visual similarity stores perceptual hashes, tagger output and For You swipes. Skipped posts are never stored, and signature rows can carry large embeddings.

## Decision

- `ForYouVerdictEntity` has no foreign key to posts: skipped posts are not stored, so the row keeps the preview address to show or hash the picture later. A swipe landing after its profile was deleted is refused by the repository's write guard. Room keeps one verdict per profile and post, so a later swipe replaces the earlier one. Undo removes a verdict only while its decision timestamp still matches, so it cannot erase a newer swipe on the same post.
- `VisualSignatureEntity` rows follow their post (deleted with it). A null `phash` with `phash_checked` means the picture was looked at and cannot be hashed, so it is not fetched again; without `phash_checked` the row came from the tagger and no hash was tried (a null `phash` alone does not say which). `frame_state` is 0 (still only), 1 (still of a post that plays, frames to read) or 2 (still plus frames); `frame_attempts` counts failed frame reads and at the cap the still analysis stands.
- `observeHashed` selects only the hash columns, since listing hashes must not load embeddings.
- Rows exist only while their post is stored and are never backed up. Writes for posts that are not stored are ignored or skipped.
- `put` records a hash, or null for a picture that cannot be hashed, and keeps what the tagger stored; `putVision` keeps an existing hash.
- A null embedding and null tags mean the model that recorded the row could not analyse the picture, so that model does not retry it; another model still analyses it. The embedding is opaque bytes.
- Predicted tags are stored one per line as `<per-mille score> <g|c> <tag>`, where `g` is a general tag and `c` a character tag. A malformed line is skipped on read.
- `VisionFrames` (NONE, WANTED, READ) is stored by position; its order must not change.
- A failed frame read is recorded against the model's analysis, which keeps standing. A repeating failure (`permanently`) sets the frames aside; others retry until `MAX_FRAME_ATTEMPTS` (3). `unwantFrames` marks analyses of posts that do not play as having read everything on offer.
- `clearVision` forgets everything the tagger stored for any model, since its files were deleted; picture hashes stay.

## Consequences

Hash and tagger passes can run independently and resume. Adding a frame state or cap changes both the entity and the progress queries.

## Applies to

- `core-data-android/src/main/java/com/theoriacodex/data/android/room/ForYouVerdictEntity.java`: `ForYouVerdictEntity`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomForYouVerdictRepository.kt`: `RoomForYouVerdictRepository`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/VisualSignatureEntity.java`: `VisualSignatureEntity`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/VisualSignatureDao.java`: `observeHashed`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/VisualSignatureRepository.kt`: `VisualSignatureRepository`, `PictureVision`, `VisionFrames`, `MAX_FRAME_ATTEMPTS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/PredictedTagCodec.kt`: `PredictedTagCodec`

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomVisualSimilarityStorageTest.kt`: `a profile keeps its newest verdict per post up to the cap, and undo or removal forgets them (ADR 0017)`, `signatures exist only for stored posts and list the library posts still missing one (ADR 0017)`, `vision rows follow their post when it is deleted (ADR 0017)`, `a post only the tagger saw still waits for its hash, and a post the tagger cannot read is not retried (ADR 0017)`, `a hash written after the tagger keeps its vision, and vision written after a hash keeps the hash (ADR 0017)`, `a still analysis awaiting frames is listed until its frames are read or its reads run out (ADR 0017)`, `analyses of posts that do not play stop waiting for frames and count as analysed (ADR 0017)`, `frame states are stored as 0 for a still, 1 for frames to read and 2 for frames read (ADR 0017)`, `clearing vision keeps hashes and drops rows that held only vision (ADR 0017)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomUpgradeJourneyTest.kt`: `version 13 adds a verdict log without a post and signatures that follow their post (ADR 0017)`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/VisualSignatureDao.java`: `Flow<List<HashRow>> observeHashed();`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/PredictedTagCodecTest.kt`: `tags with punctuation survive a round trip at per-mille precision (ADR 0017)`, `scores are clamped on encode and malformed lines are skipped on decode (ADR 0017)`
- Left to prose: that signatures and verdicts are never backed up rests on `BackupLibrary` having no field for them, which only review of a new field would catch; the pin keeps `observeHashed` on `HashRow`, not `HashRow` free of an embedding column nor its query from selecting every column, so loading embeddings there is unguarded; the embedding being opaque bytes is a usage rule.
