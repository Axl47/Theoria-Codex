# 0024. Profile backups are a versioned, strictly validated document written at the lowest version that fits

Status: Accepted

## Context

A backup is read by older and newer app builds, and by the restore journal. Older apps refuse a version they do not know, so a backup that silently drops newer data on an old app would be worse than a refusal. The codec replaced a Gson tree, and the backup screen shows some parse errors verbatim while others reach a generic message.

## Decision

- Version 2 added `customSources`, 3 `library.series`, 4 `extensionRepos` and 5 `statistics.watchedTagFloors`. A backup is written as version 5 when its statistics hold a watched-tag floor, otherwise 4 when it has repos, otherwise 3 when it has series, otherwise 2 (the lowest version written, whether or not it has `customSources`). An app refuses a version it does not know, so one that would drop newer data never restores it silently; this build reads versions 1 to 5. An older build would read a capped watched table without its floors as tags never watched.
- The frame is a JSON tree checked field by field; settings, statistics, posts and sites are their storage records through `DurableJson`, which writes the keys, omitted nulls and present defaults older builds read. A null `queryHash` is still written as an explicit null.
- Readers match what the Gson tree accepted (`isBackupNumber`, `isBackupBoolean`, `backupObject`) so a malformed field fails with the same message; kotlinx's `IllegalArgumentException` text is shown verbatim, other failures keep the generic message.
- An advisory field does not raise the version: an optional key whose loss on an older build is cosmetic, inside a record that build already decodes with unknown keys ignored (`DurableJson`). Refusing the whole backup on a rollback would cost far more than the hint. A chapter's `pageOffset` is the first; a field whose loss would change what the user has (a like, a watched count, a setting) still raises the version.
- Lists are bounded by sanity limits (256 repos, 256 sites, 10,000 series, 20,000 entries per series).
- Credentials are never part of a backup. Trust for extensions is granted per device and kept outside the settings record a backup carries.
- Series pages, downloads and `chaptersShareFirstPage` are not backed up; pages need the extension installed, and the Viewer rediscovers the flag. The cover is the address the source gave, fetched by the series' own source client.
- Export tolerates what extensions produce: series without an address and chapters without one are left out, a blank title is written as the address, and a series over the entry limit is cut in a fixed priority (Continue's entry, entries with progress, listed, dropped). Restore still refuses such input, since no export writes it. `validateBackupSeries` is the one check on both paths.
- A memo is kept only if it is a JSON object within `MAX_MEMO_LENGTH`; null stays eligible for a later restore to fill. The series record keys `lastPage`/`pageCount` predate anime and hold position/length for both kinds; `pageOffset` is the fraction of `lastPage` a vertical reader had reached (ADR 0192), written only while it belongs to that page, and a restore that brings a chapter as far or further takes it.
- Rows of the retired For You history section (`FYP`) are dropped on import.
- The previous release reads backups this build writes, within the versions it supports: same keys, nulls omitted, defaults present.
- An `IllegalArgumentException`'s message reaches the backup screen verbatim; parser errors fall through to its generic text.
- Optional parts are omitted when absent (no `extensionRepos` field or version bump without repos; no cover field without a cover).
- Retired fields, such as the series check time, are ignored on restore and no longer written.
- A series memo that no build writes, an empty object, or one longer than the app stores restores as none instead of failing.
- Trust for extensions is never in a backup.
- A converted For You search takes a stable id, so importing the same backup again adds nothing: when its generated id or search already exists, the generated pin gives way and the existing one stays (0020).

## Consequences

Adding a backup field means bumping the version only when the field is present, unless it is advisory, and keeping the old keys readable. Relaxing the strict readers would change user-visible error messages.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupCodec.kt`: `PROFILE_BACKUP_VERSION`, `version`, `ProfileBackupCodec`, `withoutForYouSection`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupJson.kt`: `parseBackupObject`, `rejectMalformedBackup`, `isBackupNumber`, `isBackupBoolean`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupModels.kt`: `ProfileBackup.customSources`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesBackupRecords.kt`: `BackupSeriesRecord`, `storedMemo`, `validateBackupSeries`, `writable`, `withinEntryLimit`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupService.kt`: `ProfileBackupService`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: backup encoding tests

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `a chapter's place inside its page is backed up with its page without raising the version (ADR 0024, 0192)`, `a chapter key this build does not know is ignored, as the previous release ignores pageOffset (ADR 0024)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `series round trip as version 3 and a backup without them is written and read as before (ADR 0024)`, `a backup carries the extension repos, and one without them stays readable by the previous release (ADR 0024)`, `statistics with a watched-tag floor are written as version 5 and restore the floor (ADR 0024)`, `a backup the app writes is one it restores even for a series over the chapter limit (ADR 0024)`, `a backup whose For You pins convert to fit the limit restores, and restores again (ADR 0024, 0025)`, `decoding refuses a repo, site or series list one past its limit, and takes each at it (ADR 0024)`, `a series' shared-first-page flag is never backed up (ADR 0024)`, `rows of the retired For You history section are dropped on import (ADR 0024)`, `a backup is written at the highest version any of its contents needs (ADR 0024)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupSiteProfilesTest.kt`: `version 1 backups still decode and versions below 1 or past 5 are refused (ADR 0024)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `a backup written now is the document the Gson codec wrote (ADR 0024, 0033)`, `malformed JSON fails as before while invalid fields keep their message (ADR 0024)`, `backups holding retired settings and statistics keys still import (ADR 0024)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SiteProfileRecordsTest.kt`: `records round trip every profile field through JSON without credentials (ADR 0024, 0031)`
- Left to prose: credentials cannot reach a backup because they live in `:app`, which `:core-data` cannot depend on; the backup screen showing an `IllegalArgumentException`'s text verbatim is `:feature-settings` wiring; the 20,000-entry series limit is pinned on the export side only, since a decode-side case needs a 20,001-entry valid series.
