# 0031. User-added site profiles are stored without credentials, skip invalid records, and merge on restore without the site limit

Status: Accepted

## Context

User-added sites live in `custom_sites_v1.json` and in backups (`customSources`). A single bad record must not lock the user out, and restoring a backup must always be able to finish, including journal recovery after a crash.

## Decision

- `SiteProfileRecord` never holds credentials. Every field has a default so a partial record decodes; validation happens in `toDomainOrNull`.
- The local store skips records that fail validation and writes them back with the rest for a newer app. They are kept as typed records, so keys this version does not know are dropped, and null entries are not kept. The backup decoder treats the same null as an invalid backup.
- `mergeRestored` adds absent ids, keeps already stored profiles, and never applies `SiteProfile.MAX_CUSTOM_SITES`. Backup preflight enforces the limit, so a site added after preflight cannot block journal recovery. Sites already on the device add nothing and never count against the limit.
- Data keyed by a custom source survives every store whether or not a profile exists; availability is decided elsewhere.

## Consequences

The stored list can hold more sites than the limit after a restore. Tightening validation must not make previously stored records fatal.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SiteProfileRecords.kt`: `SiteProfileRecord`, `toDomainOrNull`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SiteProfileListPolicy.kt`: `mergeRestored`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SiteProfileRepository.kt`: `mergeRestored`, `SiteProfileMergeResult`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SiteProfileRecordsTest.kt`: `records round trip every profile field through JSON without credentials (ADR 0024, 0031)`, `invalid records decode to null (ADR 0031)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreSiteProfileRepositoryTest.kt`: `unreadable records are skipped and written back field for field, without keys this version does not know (ADR 0021, 0031)`, `the site limit applies to adds but never to restores (ADR 0031)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupSiteProfilesTest.kt`: `a null site entry rejects the whole backup (ADR 0031)`, `journal recovery finishes even if sites filled the device after preflight (ADR 0025, 0031)`, `a conflicting site keeps the device profile and is reported (ADR 0031)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/CustomSourceIdentityTest.kt`: `post payloads and offline manifests keep a custom source (ADR 0031)`, `a backup with custom rows and no site profile restores them (ADR 0031)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepositoryTest.kt`: `likes and Codex items from a custom source round trip without a site profile (ADR 0031)`
- Left to prose: custom-source survival is proven for post payloads, offline manifests, backups and Room rows; settings, Recents and statistics hold custom keys by the same parse but have no test of their own, so that part is unguarded.
