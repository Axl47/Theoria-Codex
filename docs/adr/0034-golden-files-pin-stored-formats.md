# 0034. Golden files are bytes real builds wrote and are never regenerated to pass a change

Status: Accepted

## Context

Installed copies of stores, backups, journals and manifests must keep loading unchanged across refactors and the move from Gson to kotlinx.serialization.

## Decision

- Goldens (`golden/backup`, `golden/datastore` (some of them hand-built edge cases), offline manifests, query store, recent-search payloads, pinned searches, source identity) are the exact bytes production wrote, or output captured from a build that wrote that format. Tests assert what they must keep decoding to, not the bytes a future encoder emits.
- Never regenerate or rewrite an existing golden to make a refactor pass; add a new golden captured from current output when a new property needs pinning. The data behind a golden (`goldenBackup`) is edited only together with its regenerated golden.
- `assertStoredFormat` requires each golden to decode to its pinned value, each pinned value to survive a round trip, and the goldens together to hold a non-null value for every property the serializer declares, at any depth, including list items and map values. A property no golden holds fails by path: add a golden captured from current output that sets it. A renamed key decodes to its default and fails the pin only when the golden's value for it is not that default.
- Source identity goldens were captured while `SourceKey` was an enum; opening identity to custom sites must leave built-in output byte-identical. On mismatch the actual output is written to `build/golden-actual/`.
- Odd DataStore files are pinned for which inputs repair and which reset the store (original bytes kept as `.corrupt`). Under Gson a null on a defaulted primitive repaired, a null on a non-null list, map or string reset the store, and a null nested in a record threw on every read without quarantine; `coerceInputValues` now repairs all of them to the default.

## Consequences

A failing golden means compatibility broke, not that the golden is wrong.

## Applies to

- `core-data/src/testFixtures/kotlin/com/theoriacodex/data/storage/StoredFormat.kt`: `assertStoredFormat`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `BackupGoldenTest`, `goldenBackup`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreGoldenTest.kt`: `DataStoreGoldenTest`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreFormatTest.kt`: `DataStoreFormatTest`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SourceIdentityGoldenTest.kt`: `SourceIdentityGoldenTest`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/OfflineManifestGoldenTest.kt`: `OfflineManifestGoldenTest`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/QueryStoreGoldenTest.kt`: `QueryStoreGoldenTest`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/RecentSearchPayloadGoldenTest.kt`: `RecentSearchPayloadGoldenTest`
