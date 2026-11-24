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
