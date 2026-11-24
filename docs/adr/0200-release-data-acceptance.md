# 0200. The minified acceptance APK writes and reads the stored formats across a process restart, and loads the Gson-written files it holds

Status: Accepted

## Context

R8 can strip or rename what kotlinx.serialization, protobuf and Room payloads rely on, and a unit test on the unminified build cannot see that. Installed files were written by Gson (HTML escapes, since-retired keys) and must still load, and an interrupted restore or deletion must finish after a restart.

## Decision

- `ReleaseDataAcceptanceActivity` is installed only in the minified acceptance APK. It runs a `write` phase and a `read` phase, which straddle a process restart, under a random token that names every file, database and Keystore alias it creates; the read phase deletes them. It covers update state, exported stats and collection files, posts, offline manifest, query store, tag suggestions, the DataStore files, backups and pinned searches, credentials, Room payloads, and record files.
- Every format is checked against what this build writes after the restart, with its expected keys (readable keys, null values omitted as Gson omitted them). Update state, stats share, video post, credentials, settings, UI restore, statistics, sites, positions, backups and pins also load literal files a Gson build wrote (`SHIPPED_*` and `LEGACY_*` constants, with Gson's HTML escapes, Base64 padding escaped as `=`, and since-retired keys). The query store and the collection export are checked only against current fixtures and expected keys, with no literal Gson file. The values behind `SHIPPED_BACKUP` are edited only together with a backup a Gson build wrote.
- Interrupted work is left pending in the write phase and finished in the read phase by a fresh owner: a restore pending as after a crash during the library import (`ProfileRestorePendingException`, journal decoded and completed), and profile removals pending as after a crash during the library deletion. Room deletion itself is unit-tested.
- Mihon-family backups decode through protobuf models, which R8 must keep with their field numbers.
- Credential envelopes: one written by this build, one as the Gson store wrote it, and two corrupt ones that must ask for reconnection. Each case has its own Keystore key, deleted in the read phase.
- A Gson stats export predates series time, which reads as zero. A For You search row reads as Unified with an entry of tags for every source it searched, so it never fails a read.

## Consequences

A rename or an R8 rule change that breaks a stored format fails on a device before release. Editing a shipped constant defeats the check; add a new one instead.

## Applies to

- `app/src/releaseAcceptance/java/com/theoriacodex/app/acceptance/ReleaseDataAcceptanceActivity.kt`: `ReleaseDataAcceptanceActivity`, `verifyData`, `verifyCredentials`, `verifyRoomPayloads`, `verifyStatsShare`
- `app/src/releaseAcceptance/java/com/theoriacodex/app/acceptance/BackupAcceptance.kt`: `writeBackups`, `verifyBackups`, `SHIPPED_BACKUP`, `SHIPPED_PINS`
- `app/src/releaseAcceptance/java/com/theoriacodex/app/acceptance/DataStoreAcceptance.kt`: `writeDataStores`
- `app/src/releaseAcceptance/java/com/theoriacodex/app/acceptance/RecordFileAcceptance.kt`: `verifyRecordFiles`
