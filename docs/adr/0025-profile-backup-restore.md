# 0025. Restore merges through a durable intent and only adds to what the device has

Status: Accepted

## Context

A restore can be interrupted by a crash or repeated by the user, and it touches Room, settings and sites that commit separately. Data already on the device must never be lost to it.

## Decision

- Restore merges by the backup's own identities through a durable journal, so repeating it or retrying after a crash adds nothing twice. Startup finishes `recoverPendingRestore` before exposing repository-backed routes. Nothing on the device is deleted: existing profiles, Codices, items, Likes, posts, sites and follows stay, and settings merge adds what is missing with existing values winning. The merge does change some things:
  - Imported preferences (search bar, Codex sort, runtime, cache, content filters, Viewer) replace the device's only when the caller asks (`applyPreferences`).
  - `selectImportedProfile` makes the imported profile the active one.
  - An imported pinned search replaces an ordinary local pin with the same id.
  - A newer Recents entry or reading position replaces an older one (an older Recents entry only raises media progress), and Recents are pruned to their caps afterwards.
- The journal is only written after checks that recovery would also pass: the metadata limit is enforced before writing (a journal `readJournal` would refuse could not be finished), and the site limit is preflighted only before the journal exists, since a site added later must never make recovery fail (the limit may be slightly exceeded).
- The pinned-search limit is checked against the merged result, counting For You pins as they will convert.
- A journal written before restores kept the backup's IDs may already have committed with them, so `forLegacyRestore` is the only way to finish it.
- A profile deleted earlier in the process is reinstated in the write guard when its restore commits, so it is usable before a restart.
- Settings are validated against the backup frame instead of going through legacy migration, whose fallbacks lose data.
- Mihon-family import adds series in one Room transaction (no journal needed), keeps the further progress of each side, then restores repos outside the lock; a repo failure is reported against the repos alone.
- `ReadOnlyProfileBackupFiles` reads the stores' files for developer export without opening them: no startup, migration, quarantine or recovery writes. It refuses while a restore or profile deletion is pending, and `requireUnchanged` fails the export if the settings, pinned-search or reading-position files changed while the library snapshot was taken.
- Preview reads details only: a device that cannot list repos or sources leaves them out rather than refusing the backup; unlistable sources count as missing.

## Consequences

Any new restore step must be idempotent and safe to run during startup recovery.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupService.kt`: `restore`, `importSeries`, `preflightSites`, `newRepos`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupRestorePlan.kt`: `forLegacyRestore`, `mergeRestoredSettings`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupSettingsValidation.kt`: `validateBackupSettings`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileBackupModels.kt`: `ProfileBackupStore.merge`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadOnlyProfileBackupFiles.kt`: `ReadOnlyProfileBackupFiles`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `restoring the same backup twice adds nothing the second time (ADR 0025)`, `failure after database commit resumes idempotently from durable journal (ADR 0025)`, `restore merges into matching profiles and preserves current preferences and content (ADR 0025)`, `global preferences are restored only when explicitly requested (ADR 0025)`, `developer copy selects the imported active profile and repeats without duplicate identities (ADR 0025)`, `invalid backup and capacity overflow fail before any durable restore mutation (ADR 0025)`, `a backup whose For You pins convert to fit the limit restores, and restores again (ADR 0024, 0025)`, `a journal from before identity merges finishes under the IDs it already committed (ADR 0025)`, `a profile deleted this session is usable again once restored, and others stay retired (ADR 0025, 0026)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedSavedSearchRepositoryTest.kt`: `an incoming ordinary search replaces the local one with its ID (ADR 0020, 0025)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionRepositoryTest.kt`: `bounded merge is idempotent preserves newer local positions and unrelated entries (ADR 0025)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomRecentsRepositoryTest.kt`: `restore preserves exact rows and keeps newer activity (ADR 0025)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomProfileBackupStoreTest.kt`: `a restore that trims Recents deletes posts only the trimmed rows held (ADR 0025)`, `a restore onto a series the device already opened merges progress and lists nothing the device lacks (ADR 0014, 0025)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupSiteProfilesTest.kt`: `a restore that would pass the site limit fails before any journal is written (ADR 0025)`, `journal recovery finishes even if sites filled the device after preflight (ADR 0025, 0031)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/TachiyomiBackupTest.kt`: `repos that cannot be read leave the series import standing (ADR 0025)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadOnlyProfileBackupFilesTest.kt`: `read-only snapshot preserves settings bytes and detects later atomic replacements (ADR 0025)`, `corrupt future and pending stores fail without quarantine or recovery writes (ADR 0025)`, `files a Gson build wrote decode without being rewritten (ADR 0025)`
- Left to prose: that startup finishes `recoverPendingRestore` before opening repository-backed routes is ordering inside `TheoriaAppContainer.awaitDurableStores`, which has no host test seam, so it is unguarded; that `forLegacyRestore` is the only way to finish an old journal, that the Mihon-family import is one Room transaction, and that every new restore step is idempotent are rules for whoever adds a step; settings skipping legacy migration is a design choice with no single call to assert.
