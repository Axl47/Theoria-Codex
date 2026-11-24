# 0032. Stores open through a startup gate, refuse newer schemas, and set unreadable files aside before resetting

Status: Accepted

## Context

Stores are collected while the app is still being built, and a rollback to an older build can meet files a newer build wrote. Overwriting the only copy of a user's data, crashing a collector, or blocking the UI thread on storage are all unacceptable.

## Decision

- `ApplicationDataReadiness` is a lazy, process-lifetime boundary: construction does no work, `start` is non-blocking, concurrent `awaitReady` callers share one attempt, and a failed attempt retries only on explicit `retry`. DataStore repositories themselves stay free of constructor I/O.
- `JsonDataStore.awaitReady` opens the file. A failure (written by a newer build, or unreadable and impossible to set aside) records `DurableStorePhase.FAILED`, rethrows so the startup gate shows its failure screen, and changes nothing on disk. `data` never fails because the store could not open; collectors wait until a later `awaitReady` (the Retry) succeeds. Every store that can fail must be awaited by the gate.
- The top-level `schemaVersion` is checked before the typed decode; a newer one throws `UnsupportedStoreSchemaException`, so a payload in a new shape is never reset.
- An unreadable file is renamed by `setAsideCorruptFile` to `<name>.corrupt-<size>-<sha256>` (rename needs no free space and leaves either name on disk if the process dies), recorded in the recovery registry, then replaced by the default. If it cannot be set aside, the error propagates and the live file keeps its bytes. The name is content-addressed so a later failure never replaces an earlier set-aside file; identical bytes already set aside by an interrupted recovery are accepted, different bytes are refused.
- The registry re-lists set-aside files from earlier processes on first access, checking the checksum only when the size matches the name; a mismatched copy is left out but never blocks the store.
- DataStore runs transforms in the caller's context, so `updateDataInBackground` moves them to `Dispatchers.IO`, off the caller's (often main) dispatcher. The caller's cancellation still applies.
- `openDecodedJsonDataStore` and `JsonDataStore.update` hold `decode(encode(x))`, what a later read would produce, so memory never shows a value the file cannot carry. An unchanged result writes nothing.
- `AtomicJsonFileStore` sets aside non-UTF-8, blank, JSON-null or serializer-rejected files; other failures, such as an `IllegalArgumentException` from a record's checks, propagate. `writeIntent` flushes a bounded intent and its new directories before the caller starts irreversible work.

## Consequences

A store that can fail to open must be registered with the startup gate. Changing a schema requires bumping `schemaVersion` knowing older builds will refuse the file.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/ApplicationDataReadiness.kt`: `ApplicationDataReadiness`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreStorage.kt`: `JsonDataStoreSerializer`, `JsonDataStore`, `openJsonDataStore`, `openDecodedJsonDataStore`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreOperations.kt`: `updateDataInBackground`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/FileStoreRecoveryRegistry.kt`: `FileStoreRecoveryRegistry`, `setAsideCorruptFile`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/AtomicJsonFileStore.kt`: `AtomicJsonFileStore`, `writeIntent`
