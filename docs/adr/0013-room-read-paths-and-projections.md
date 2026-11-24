# 0013. Room reads avoid transactions and reuse decoded posts

Status: Accepted

## Context

Likes, imports and cleaning serialize on Room's transaction executor, and every viewed post writes `posts`, which would wake broad observers. Decoding post payloads repeatedly is costly.

## Decision

- Single-statement reads (`hasLibraryData`, media duration `get`) skip `withTransaction` and use WAL readers, so they never queue behind writes. Only an expired duration retry uses a transaction, re-reading the row first.
- Writes that change nothing touch no row: `raiseWatchedMediaProgress` updates only when progress rises; `recordProgress` and the read-state, follow and setting updates write only when the stored value would change (a series listing refresh, `storeEntries`, does upsert the series row and every current entry each time); `observeHasFollowed` reads only `series` so entry progress never wakes it.
- The library read reruns only on membership writes (even ones leaving the same ids, since a read can fall between a removal and its Undo), not on every `posts` write.
- `RetainedPostPayloads` keeps the Posts of the latest projection so unchanged payloads are not decoded again; callers sharing an instance must project the same rows. Every sort of a Codex shares one projection, and all watched Recents collectors share one.
- `RetainedPostPayloadCache` is a small LRU that outlives one collection; `trim()` runs on each emission and evicts projections above `maxRetainedPosts`, then least recently used ones while there are more than `maxEntries` or more retained posts than `maxRetainedPosts` in total, so large libraries do not become a process-wide cache. Eviction removes the projection from the cache only; a collector already holding its projection keeps decoding into it for as long as it collects, and the next `projection(key)` call starts a fresh one.
- Room emits null for a single-row query with no match despite the Java signature, so the flow is mapped as nullable.

## Consequences

Reads can observe the last committed state, not in-flight transactions. Changing observer granularity requires revisiting these query choices.

## Applies to

- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepository.kt`: `hasLibraryData`, library and Codex post flows
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomMediaDurationRepository.kt`: `get`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RetainedPostPayloads.kt`: `RetainedPostPayloads`, `RetainedPostPayloadCache`, `decodePostSnapshots`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomRecentsRepository.kt`: `watchedPayloads`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomSeriesRepository.kt`: series flow mapping
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/RecentsDao.java`: `raiseWatchedMediaProgress`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/SeriesDao.java`: `observeHasFollowed`

## Guarded by

- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomReadIsolationTest.kt`: `single reads complete while an unrelated write transaction is open (ADR 0013)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomSeriesRepositoryTest.kt`: `a write that changes nothing does not wake series observers (ADR 0013)`, `entry progress does not wake the has-followed query (ADR 0013)`, `observing a series that is not stored emits null (ADR 0013)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomRecentsRepositoryTest.kt`: `watched media progress keeps the highest page per membership (ADR 0013)`, `progress on one watched post reuses every other decoded post (ADR 0013)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepositoryTest.kt`: `the library rereads its posts when membership changes, not when a saved post's data does (ADR 0013)`, `every sort of a Codex reuses one decoded copy of its posts (ADR 0013)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RetainedPostPayloadsTest.kt`: `unrelated invalidations decode zero posts and one changed payload decodes once at every library size (ADR 0013)`, `equal Java string hashes do not grant authority to a different payload (ADR 0013)`, `returning to a recent Codex reuses its posts while oversized projections are not retained (ADR 0013)`, `the least recently used projection goes once there are more than maxEntries, and its collector keeps decoding into it (ADR 0013)`, `small projections that together pass the post cap lose the least recently used one (ADR 0013)`
- Left to prose: that an expired duration retry re-reads its row inside its transaction is a race no test drives (the delete itself is covered in `RoomMediaDurationRepositoryTest`); a library read rerunning on a membership write that leaves the same ids is invisible behind its deduplicated result; that callers sharing a `RetainedPostPayloads` project the same rows is a usage rule.
