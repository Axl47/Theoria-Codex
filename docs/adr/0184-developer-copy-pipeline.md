# 0184. Copying production data into Debug reads production read-only, pins both signers and never exposes secrets

Status: Accepted

## Context

A developer can seed the Debug app with the library and source credentials of the production app. The production package and its data must not be changed by this, and the snapshot carries provider tokens and API keys. The copy runs only in builds that opt in, so it is compiled into Debug and the opt-in `personalRelease` variant and excluded from public builds.

## Decision

- `DeveloperCopyProvider` serves one read-only snapshot at `content://<authority>/snapshot`. It is registered in the manifest only by the opt-in `personalRelease` variant (and Debug), in its own `:developerCopy` process, and runs only in the production package with `DEVELOPER_COPY_ENABLED`, authenticates the calling UID against the Debug package and its pinned certificate (`requireCopySigner`, a 64-hex-digit SHA-256), allows one export at a time, and gives up after `COPY_TIMEOUT_MS`.
- `DeveloperCopyActivity` runs in Debug. A host script (`scripts/copy_production_to_debug.py`) places a one-shot `request.json` in Debug's private storage with `run-as` before launching it, after verifying that the installed Debug and production APKs both match the intended signed builds (`require_installed_apk`), so nothing in Debug is touched otherwise. The Activity accepts the request once (it deletes the file), only if it names the job, is under 60 seconds old and carries a 64-hex-digit production certificate, and it checks that the production package and its exported provider match that signer. It publishes only a non-secret outcome (`complete` or `failed`) to `status.json`, which the script polls.
- Production is never written or checkpointed. `stageReadOnlyLibrary` reads rows through a read-only SQLite connection that includes committed WAL rows into a disposable staging database, and never copies live database files or opens production through Room's writable helper; Room's existing backup mapper then converts the staged copy. The read fails if `PRAGMA data_version` changes during the read, so an external commit between cursor windows is rejected. The export also fails if the settings files or the accounts changed meanwhile.
- Credentials are read without atomic recovery; if the store needs recovery the export fails and tells the user to open production normally first. Secrets exist only in the authenticated pipe and in each app's memory and Keystore storage. `DeveloperCopyPayload.toString` is redacted, credential parse failures carry no message or cause because the parser's errors quote the input, and the provider never exposes exception messages, which can contain tokens or URLs; a failed pipe closes with a fixed message.
- The credential section omits absent values; older builds wrote them as JSON null, and decoding still accepts that.
- Restore into Debug recovers any earlier admitted restore first, and the backup keeps a stable document identity so retrying an unchanged snapshot is idempotent.

## Consequences

The copy cannot corrupt production, and a failure leaks nothing but its outcome. A change of either app's signing certificate requires the matching pinned fingerprint to change.

## Applies to

- `app/src/developerCopy/java/com/theoriacodex/app/developercopy/DeveloperCopyProvider.kt`: `DeveloperCopyProvider`
- `app/src/developerCopy/java/com/theoriacodex/app/developercopy/DeveloperCopyActivity.kt`: `DeveloperCopyActivity`, `consumeRequest`, `importCopy`, `writeStatus`
- `app/src/developerCopy/java/com/theoriacodex/app/developercopy/DeveloperCopyIdentity.kt`: `requireCopySigner`
- `app/src/developerCopy/java/com/theoriacodex/app/developercopy/DeveloperCopyProtocol.kt`: `DeveloperCopyPayload`, `decodeCredentials`, `DeveloperCopyCredentials`
- `app/src/developerCopy/java/com/theoriacodex/app/developercopy/ReadOnlyLibrarySnapshot.kt`: `stageReadOnlyLibrary`, `withUnchangedReadOnlyLibrary`
