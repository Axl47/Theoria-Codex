# 0016. Posts are stored once, sparsely and compared by decoded value

Status: Accepted

## Context

Save, Like and Recents may hold a route snapshot before media resolution finishes. Payloads written by older builds used Gson, which escaped `& = < > '` as `\u0026`-style sequences, while kotlinx writes them raw, so the same post can have different bytes.

## Decision

- Codex membership, post snapshots and likes live in one database through `RoomCodexLikesRepository`, so a like cannot commit without its system-Codex membership and an unlike cannot leave a stale item. A deleted Codex's name is only reused on Undo when no Codex took it meanwhile. Comparison memberships keep shared posts; no payload or offline file is removed.
- `SharedPosts` writes sparsely: absent fields never erase richer stored data, new values refresh the payload, and a row is rewritten only when its decoded value changes. Compare decoded values, never payload strings.
- `LocalPostPayloadCodec` requires an explicit current schema version, validates record shape and checks that the JSON identity matches the row key, so corrupt or future payloads are not mistaken for missing posts.
- Golden payload files written by the Gson encoders stay frozen, and frozen v1 payloads exercise genuine backward reads. Later serializers must keep reading them; goldens for fields Gson never wrote were captured from kotlinx.
- Mappers share row/domain conversion between repositories and the backup store. Lenient readers (`OrNull`) skip rows from unknown sources; the backup snapshot reads strictly so a backup never silently drops rows. Unreadable like tags degrade to none rather than hiding the like.

## Consequences

Byte-level equality of payloads cannot be relied on. Changing the encoder requires keeping the goldens passing.

## Applies to

- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepository.kt`: `RoomCodexLikesRepository`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/SharedPosts.kt`: `SharedPosts`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/LocalPostPayloadCodec.kt`: `LocalPostPayloadCodec`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomMappers.kt`: `toLikedPostOrNull`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomPayloadGoldenTest.kt`: golden tests

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepositoryTest.kt`: `like and system Codex membership toggle and clear atomically (ADR 0016)`, `database failure rolls back both like and system Codex (ADR 0016, 0018)`, `unsupported and malformed local Post payloads fail explicitly (ADR 0016, 0038)`, `a payload under another post's key or without a schema version is refused, not read as a missing post (ADR 0016)`, `rows from a retired source are skipped and a like with unreadable tags is still listed (ADR 0016)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomProfileBackupStoreTest.kt`: `deleting a Codex for Undo restores its place rules and otherwise orphaned posts (ADR 0016)`, `a snapshot holding a like from an unknown source fails instead of dropping it (ADR 0016)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCollectionStorageTest.kt`: `watched eviction cleans affected identities while preserving other memberships (ADR 0016)`, `every sparse collection write preserves previously resolved shared media (ADR 0016)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomPayloadGoldenTest.kt`: `golden posts payload decodes to the original post (ADR 0016)`, `golden full posts payload decodes every field (ADR 0016)`, `golden liked tags decode to the original tags (ADR 0016)`, `upserting an unchanged post keeps the Gson-written row (ADR 0016, 0038)`, `upserting a post with sparser tags and creators keeps the row (ADR 0016)`, `upserting a changed post rewrites the row (ADR 0016)`
- Left to prose: the decode tests catch a reader that stops reading the frozen goldens, not someone regenerating a golden from the new encoder; which goldens are Gson-era is history, and pinning their bytes would only restate them. A deleted Codex keeping its name when no other Codex took it is not asserted separately. That no offline file is removed with a membership rests on the Room store never touching files.
