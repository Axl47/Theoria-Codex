# 0151. Like writes are serialized and treat every site's copy of a file as one like

Status: Accepted

## Context

A post can exist under several sites' IDs (mirrors of the same file), and likes are written from several surfaces at once. A check ("is it liked?") followed by a change has to see no other write between them, and undoing a like must not disturb a newer one.

## Decision

- `LikesCodexSyncService` serializes `toggle`, `likeIfAbsent` and `removeLike` with one mutex, so each one's check and change see no other of those writes in between. The bulk operations (`clearProfile`, `restoreClearedLikes`) are single transactions and do not take the mutex.
- `toggle` likes the post, or, when its file is already liked under any site's ID (`FileMirrorIndex`), removes every one of those likes, so a heart filled by another site's copy empties.
- When removing the copies, the removal reads only the ID; the stored copy is passed to the write (the given post for its own ID, otherwise `codexRepository.getPost`) so a concurrent change can never like the wrong post.
- `likeIfAbsent` likes only when neither the post nor another site's copy of its file is liked, and returns the like it added.
- `removeLike` removes the given like only while the stored like of the post has the same `likedAtEpochMs`; a newer like of the post, or none, is left as it is.

## Consequences

Likes are consistent across mirror sites, and Undo of a like is safe against later likes. The three guarded writes are strictly ordered, so a slow one delays later likes.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/LikesCodexSyncService.kt`: `LikesCodexSyncService`, `toggle`, `likeIfAbsent`, `removeLike`, `clearProfile`, `restoreClearedLikes`
