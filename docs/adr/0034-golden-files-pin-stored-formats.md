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
- Media-kit goldens: `PostDownloadJobStoreGoldenTest` pins the v3 download queue (`gson-written.json` is the Gson store's own output) so queues older builds wrote restore the same jobs, including an interrupted transfer returning queued with its native download id and origin label `Codex · A=B`. `PixivUgoiraDiskCacheGoldenTest` pins the ugoira sidecar the Gson writer stored, HTML-sensitive file name included, so it decodes the same.
- `tag_suggestions.json` has three goldens in `feature-search/src/test/resources/golden/tag-suggestions`: `gson-written.json` is the Gson store's own output (HTML-escaped strings); `legacy.json` is a sparse file with a quoted count, a null source list, an unknown source and a retired key; `full.json` was captured from the kotlinx store and sets every key, because the Gson store never wrote a source namespace.
- App goldens: `UpdateStateGoldenTest` pins `update_state.json` (`UpdateStateSnapshot`, read and written with kotlinx.serialization). `full.json` is what the Gson store wrote, HTML-escaped strings included; `sparse-legacy.json` is an older, partial file with a quoted number, a section without bullets and a retired key; `kotlinx-full.json`, captured from the kotlinx store, keeps a source version and release name on the installed changelog that `full.json` lacks.
- `CredentialEnvelopeGoldenTest` pins how credential files read. An envelope file that is not parseable JSON or has the wrong shape, and a valid envelope whose decrypted payload is truncated or of the wrong shape, all read as `ReconnectRequired`, not "temporarily unavailable", whatever the JSON library. An envelope or payload from a newer build can have any shape and must report `UnsupportedVersion` with its version instead of asking to reconnect. A Gson-written envelope encrypted with the test's key decodes to every credential it held. Robolectric has no Android Keystore, so a software one is installed for each test. `CredentialPayloadRecordTest` pins the plaintext shape: custom-site keys are an additive `customSites` map, absent when empty so payloads without them keep their previous bytes; the payload golden is the plaintext the Gson store encrypted, the envelope golden is a Gson-written envelope with Base64 padding escaped as `=`.

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
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostDownloadJobStoreGoldenTest.kt`: `PostDownloadJobStoreGoldenTest`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraDiskCacheGoldenTest.kt`: `PixivUgoiraDiskCacheGoldenTest`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/TagSuggestionStoreGoldenTest.kt`: `TagSuggestionStoreGoldenTest`
- `app/src/test/java/com/theoriacodex/app/update/UpdateStateGoldenTest.kt`: `UpdateStateGoldenTest`
- `app/src/test/java/com/theoriacodex/app/sourceauth/CredentialEnvelopeGoldenTest.kt`: `CredentialEnvelopeGoldenTest`
- `app/src/test/java/com/theoriacodex/app/sourceauth/CredentialPayloadRecordTest.kt`: `CredentialPayloadRecordTest`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/storage/StoredFormatTest.kt`: `goldens that hold every property pass, retired keys and older partial files included (ADR 0034)`, `a golden that decodes differently fails (ADR 0034)`, `properties no golden holds fail by path, inside nested lists and map values too (ADR 0034)`, `a key the model does not declare fails by path unless it is listed as retired (ADR 0034)`, `a retired nested path never excuses a key spelled like it (ADR 0034)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SourceIdentityGoldenTest.kt`: `default settings file keeps its persisted bytes (ADR 0034)`, `multi-source settings file keeps its persisted bytes (ADR 0034)`, `multi-source backup keeps its encoded bytes (ADR 0034)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreGoldenTest.kt`: `settings null on a defaulted boolean repairs to the default (ADR 0033, 0034)`, `settings null on a non-null list repairs to the default and keeps the rest (ADR 0034)`, `ui restore null on a non-null map or string repairs and keeps the rest (ADR 0034)`, `statistics null inside a nested record repairs that field (ADR 0034)`, `unreadable or unsupported files surface as CorruptionException (ADR 0034)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostDownloadJobStoreGoldenTest.kt`: `a v3 queue the Gson store wrote restores the same jobs (ADR 0034)`, `the v3 download queue golden pins every stored key (ADR 0034)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/PixivUgoiraDiskCacheGoldenTest.kt`: `the ugoira sidecar older builds wrote decodes the same (ADR 0034)`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/TagSuggestionStoreGoldenTest.kt`: `a Gson-written cache loads every suggestion (ADR 0034)`, `a sparse legacy cache keeps what Gson kept (ADR 0034)`, `the full cache holds every stored key (ADR 0034)`
- `app/src/test/java/com/theoriacodex/app/update/UpdateStateGoldenTest.kt`: `update state files older builds wrote decode the same (ADR 0034)`
- `app/src/test/java/com/theoriacodex/app/sourceauth/CredentialEnvelopeGoldenTest.kt`: `malformed envelope requires reconnect (ADR 0034)`, `wrong shape envelope requires reconnect (ADR 0034)`, `malformed payload requires reconnect (ADR 0034)`, `wrong shape payload requires reconnect (ADR 0034)`, `envelope from a newer build reports its version (ADR 0034)`, `payload from a newer build reports its version (ADR 0034)`, `envelope written by the Gson store reads every credential (ADR 0034)`
- `app/src/test/java/com/theoriacodex/app/sourceauth/CredentialPayloadRecordTest.kt`: `payloads without custom keys keep their previous bytes (ADR 0034)`
- Left to prose: that an existing golden is never rewritten needs the change's git history, which belongs in a `--base` check beside `scripts/check_adrs.py` and is not built here; that every new stored format gets a golden test, and that `goldenBackup` changes only with its regenerated golden, are rules for authors with no registry of formats to enumerate.
