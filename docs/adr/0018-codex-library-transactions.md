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
