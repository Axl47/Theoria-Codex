# 0018. Cross-Codex and Likes operations commit atomically and Undo authority is process-only

Status: Accepted

## Context

Likes and system Codex memberships are owned by different repositories but must change together, and bulk reorders must not move Codices the caller never saw.

## Decision

- `CodexLikesTransactions` implementations commit each method atomically, so domain APIs stay free of Room.
- Reorder requires a complete, duplicate-free permutation of known ids (`resolveCompleteCodexOrder`), otherwise nothing is applied.
- Clearing likes removes only the matching system-Codex memberships; independently saved ones stay. `removeProfileLibrary` (profile deletion) removes all the profile's Codices, Likes and For You verdicts; posts shared with other owners stay while still referenced.
- An automatic Codex tag set matches a post when, within the post's source, every tag group has at least one tag the post carries. `postMatchesAutomaticTagGroups` implements it.
- Undo payloads (`CodexCleaningSnapshot`, `ClearedLikesSnapshot`, `DeletedCodexSnapshot`) are process-only authority and restore without overwriting newer memberships or Likes.

## Consequences

Memberships cannot be repaired after a process restart through Undo.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `CodexLikesTransactions`, `removeProfileLibrary`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CodexLikesPolicy.kt`: `resolveCompleteCodexOrder`, `postMatchesAutomaticTagGroups`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CodexCleaning.kt`: `CodexCleaningSnapshot`

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepositoryTest.kt`: `database failure rolls back both like and system Codex (ADR 0018)`, `ordinary Likes clear removes only pre-clear liked memberships (ADR 0018)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomProfileDeletionTest.kt`: `a later collection deletion failure rolls back the entire profile and preserves shared Posts (ADR 0018)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/CodexLikesPolicyTest.kt`: `complete Codex order accepts only an exact permutation (ADR 0018)`, `automatic tags match any canonical tag only within the same source (ADR 0018)`, `automatic tag groups require every group while accepting alternatives (ADR 0006, 0018)`
- Left to prose: the undo payloads stay in the process by construction (they hold domain `Post`s, which have no serializer, so saving one would take a new storage record); atomicity is checked for the methods whose failure tests exist, not for every transaction method.
