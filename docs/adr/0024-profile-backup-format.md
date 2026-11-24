# 0024. Profile backups are a versioned, strictly validated document written at the lowest version that fits

Status: Accepted

## Context

A backup is read by older and newer app builds, and by the restore journal. Older apps refuse a version they do not know, so a backup that silently drops newer data on an old app would be worse than a refusal. The codec replaced a Gson tree, and the backup screen shows some parse errors verbatim while others reach a generic message.

## Decision

- Version 2 added `customSources`, 3 `library.series`, 4 `extensionRepos` and 5 `statistics.watchedTagFloors`. A backup is written as version 5 when its statistics hold a watched-tag floor, otherwise 4 when it has repos, otherwise 3 when it has series, otherwise 2 (the lowest version written, whether or not it has `customSources`). An app refuses a version it does not know, so one that would drop newer data never restores it silently; this build reads versions 1 to 5. An older build would read a capped watched table without its floors as tags never watched.
- The frame is a JSON tree checked field by field; settings, statistics, posts and sites are their storage records through `DurableJson`, which writes the keys, omitted nulls and present defaults older builds read. A null `queryHash` is still written as an explicit null.
- Readers match what the Gson tree accepted (`isBackupNumber`, `isBackupBoolean`, `backupObject`) so a malformed field fails with the same message; kotlinx's `IllegalArgumentException` text is shown verbatim, other failures keep the generic message.
- Lists are bounded by sanity limits (256 repos, 256 sites, 10,000 series, 20,000 entries per series).
- Credentials are never part of a backup. Trust for extensions is granted per device: a restored backup always carries empty `ExtensionChoices`.
- Series pages, downloads and `chaptersShareFirstPage` are not backed up; pages need the extension installed, and the Viewer rediscovers the flag. The cover is the address the source gave, fetched by the series' own source client.
- Export tolerates what extensions produce: series without an address and chapters without one are left out, a blank title is written as the address, and a series over the entry limit is cut in a fixed priority (Continue's entry, entries with progress, listed, dropped). Restore still refuses such input, since no export writes it. `validateBackupSeries` is the one check on both paths.
- A memo is kept only if it is a JSON object within `MAX_MEMO_LENGTH`; null stays eligible for a later restore to fill. The series record keys `lastPage`/`pageCount` predate anime and hold position/length for both kinds.
- Rows of the retired For You history section (`FYP`) are dropped on import.
- The previous release reads backups this build writes, within the versions it supports: same keys, nulls omitted, defaults present.
- An `IllegalArgumentException`'s message reaches the backup screen verbatim; parser errors fall through to its generic text.
- Optional parts are omitted when absent (no `extensionRepos` field or version bump without repos; no cover field without a cover).
- Retired fields, such as the series check time, are ignored on restore and no longer written.
- A series memo that no build writes, an empty object, or one longer than the app stores restores as none instead of failing.
- Trust for extensions is never in a backup.
- A converted For You search takes a stable id, so importing the same backup again adds nothing: when its generated id or search already exists, the generated pin gives way and the existing one stays (0020).

## Consequences

Adding a backup field means bumping the version only when the field is present, and keeping the old keys readable. Relaxing the strict readers would change user-visible error messages.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupCodec.kt`: `PROFILE_BACKUP_VERSION`, `version`, `ProfileBackupCodec`, `withoutForYouSection`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupJson.kt`: `parseBackupObject`, `rejectMalformedBackup`, `isBackupNumber`, `isBackupBoolean`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupModels.kt`: `ProfileBackup.customSources`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesBackupRecords.kt`: `BackupSeriesRecord`, `storedMemo`, `validateBackupSeries`, `writable`, `withinEntryLimit`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupService.kt`: `ProfileBackupService`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: backup encoding tests
