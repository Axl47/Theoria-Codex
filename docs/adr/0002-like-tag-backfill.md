# 0002. Likes carry every tag; older Likes are filled in once

Status: Accepted

## Context

Before #329 a Like kept only its post's first 20 tags, and a post with no typed taxonomy kept none. #329 made For You learn from every tag by reading each Like's stored post and merging its tags in on every rebuild. That meant observing the Likes collection, so every Like or Save decoded the Liked posts' stored payloads again. The merge needs `trainingTagsFor` (app-logic), which Room migrations in core-data-android cannot reach.

## Decision

Likes are read from their own rows. Two paths keep older rows complete:

- A one-time backfill rewrites each Like's tags as `likeTagsWithStored`: the stored post's training tags first, then the Like's own, once per match token. It only adds tags. It runs one page of 200 Likes per Room transaction, reading and writing each row in the same transaction, so a Like or unlike in between is never overwritten. Rows whose tags or stored post do not decode, and rows without a stored post, are left as they are. A flag in the `like_tag_backfill` shared preferences is set only after the last page; an interrupted run starts over on the next launch, and rewriting a row twice changes nothing. A failed run is reported, and For You reads the Likes as they are.
- Restoring a backup applies the same merge to each restored Like, using the backup's copy of its post, so an old backup restored after the flag is set still brings full tags.

For You's tag affinity waits for the backfill before its first build, no longer observes the Likes collection, and builds the kept-tag interest map once per library change rather than on every watched post.

## Consequences

A Like or Save no longer re-decodes Liked posts. The first launch after the update pays one pass over every Like. The backfill, its flag and the restore merge can go once two releases have shipped after the one that added them; until then, removing the flag would rerun the pass harmlessly.

## Applies to

- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/LikeTagBackfill.kt`: `LikeTagBackfill`, `likeTagsWithStored`
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `keptTagEvidence`, `withStatistics`, `shareTagAffinityInputs`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomLikeTagRewriter.kt`: `RoomLikeTagRewriter`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupService.kt`: `withLikeTags`
- `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`: `likeTagBackfill`
