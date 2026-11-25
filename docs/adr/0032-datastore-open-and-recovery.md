# 0032. Stores open through a startup gate, refuse newer schemas, set unreadable files aside before resetting, and close by waiting for their release

Status: Accepted

## Context

Stores are collected while the app is still being built, and a rollback to an older build can meet files a newer build wrote. Overwriting the only copy of a user's data, crashing a collector, or blocking the UI thread on storage are all unacceptable.

## Decision

- `ApplicationDataReadiness` is a lazy, process-lifetime boundary: construction does no work, `start` is non-blocking, concurrent `awaitReady` callers share one attempt, and a failed attempt retries only on explicit `retry`. DataStore repositories themselves stay free of constructor I/O.
- `JsonDataStore.awaitReady` opens the file. A failure (written by a newer build, or unreadable and impossible to set aside) records `DurableStorePhase.FAILED`, rethrows so the startup gate shows its failure screen, and changes nothing on disk. `data` never fails because the store could not open; collectors wait until a later `awaitReady` (the Retry) succeeds. Every store that can fail must be awaited by the gate.
- The top-level `schemaVersion` is checked before the typed decode; a newer one throws `UnsupportedStoreSchemaException`, so a payload in a new shape is never reset. A store that migrates older files on decode names its oldest readable version (`oldestSchemaVersion`); anything older resets like any other invalid file.
- An unreadable file is renamed by `setAsideCorruptFile` to `<name>.corrupt-<size>-<sha256>` (rename needs no free space and leaves either name on disk if the process dies), recorded in the recovery registry, then replaced by the default. If it cannot be set aside, the error propagates and the live file keeps its bytes. The name is content-addressed so a later failure never replaces an earlier set-aside file; identical bytes already set aside by an interrupted recovery are accepted, different bytes are refused.
- The registry re-lists set-aside files from earlier processes on first access, checking the checksum only when the size matches the name; a mismatched copy is left out but never blocks the store.
- DataStore runs transforms in the caller's context, so `updateDataInBackground` moves them to `Dispatchers.IO`, off the caller's (often main) dispatcher. The caller's cancellation still applies.
- `openDecodedJsonDataStore` and `JsonDataStore.update` hold `decode(encode(x))`, what a later read would produce, so memory never shows a value the file cannot carry. An unchanged result writes nothing.
- `AtomicJsonFileStore` sets aside non-UTF-8, blank, JSON-null or serializer-rejected files; other failures, such as an `IllegalArgumentException` from a record's checks, propagate. `writeIntent` flushes a bounded intent and its new directories before the caller starts irreversible work.
- Every open-use-close of a DataStore goes through `DataStoreScopes.kt`: `withDataStoreScope { scope -> ... }`, or `newDataStoreScope()` with `closeDataStores()` when a scope spans steps. DataStore holds one active store per file and releases that claim from a completion handler on the store's scope job, which can trail `cancel()` while a write is in flight; cancel-then-reopen threw "There are multiple DataStores active for the same file" (#370, a third of reopens in a host loop with a write in flight). Closing registers a completion handler, cancels the job, and waits for that handler on `Dispatchers.IO` under `NonCancellable`. `Job.join()` is not enough: it returns once the job reads completed, while completion handlers, DataStore's release among them, still run afterwards on the completing thread. Handlers run in registration order and DataStore registers its release when the store is built, so the helper's handler fires after the release returns; `DataStoreScopesTest` fails if either library changes that.
- The wait is bounded at `DATASTORE_CLOSE_TIMEOUT` (5 s), and a timeout is an error, since the next open would fail anyway; a block that already failed keeps its own failure. The repositories take their scope with no default. The app's application scope hosts its stores beside other process-lifetime work and is never closed; any other container whose scope also runs other work gives its stores a separate `newDataStoreScope()` closed after that work stops.

## Consequences

A store that can fail to open must be registered with the startup gate. Changing a schema requires bumping `schemaVersion` knowing older builds will refuse the file.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/ApplicationDataReadiness.kt`: `ApplicationDataReadiness`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreStorage.kt`: `JsonDataStoreSerializer`, `JsonDataStore`, `openJsonDataStore`, `openDecodedJsonDataStore`, `oldestSchemaVersion`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreOperations.kt`: `updateDataInBackground`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreScopes.kt`: `withDataStoreScope`, `newDataStoreScope`, `closeDataStores`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/FileStoreRecoveryRegistry.kt`: `FileStoreRecoveryRegistry`, `setAsideCorruptFile`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/AtomicJsonFileStore.kt`: `AtomicJsonFileStore`, `writeIntent`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/storage/ApplicationDataReadinessTest.kt`: `construction is lazy and concurrent awaiters share one initialization (ADR 0032)`, `failure is sticky until explicit retry and retry publishes the recovered value (ADR 0032)`, `cancelling one waiter does not cancel process initialization (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `constructors perform no file reads or writes (ADR 0032)`, `newer settings schema fails closed without corruption replacement (ADR 0032, 0033)`, `newer ui schema fails closed without corruption replacement (ADR 0032)`, `settings corruption is backed up and resets to defaults (ADR 0032)`, `a second corruption keeps the first set-aside file (ADR 0032)`, `settings that cannot be set aside fail to open instead of resetting (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreOpenFailureTest.kt`: `a store a newer build wrote fails to open, keeps its file, and opens on retry once readable (ADR 0032)`, `a store whose unreadable file cannot be set aside fails to open and keeps its file (ADR 0032)`, `a set-aside file that no longer matches its name is left unlisted and its store still opens (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/StatisticsRepositoriesTest.kt`: `newer statistics schema fails closed without replacing the file (ADR 0032)`, `a statistics file older than its oldest readable version resets and is kept aside (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/AtomicJsonFileStoreTest.kt`: `matching quarantine completes interrupted recovery idempotently (ADR 0032)`, `different quarantine collision fails closed with live bytes untouched (ADR 0032)`, `malformed, wrong-shaped, null, blank, and non-UTF-8 files are quarantined (ADR 0032)`, `writes read back unescaped and other decoding failures propagate untouched (ADR 0032)`, `intent admission follows file sync replacement and directory sync including fallback (ADR 0032)`, `first intent also commits each newly created directory entry (ADR 0032)`, `intent sync failure and oversized UTF8 cannot replace the previous committed record (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedQueryRepositoryTest.kt`: `verified query quarantine is rediscovered after process restart (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreDispatchTest.kt`: `storage transaction and shutdown do not need the caller dispatcher to keep running (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/DataStoreStorageTest.kt`: `an update is held in the form a later read of the file gives (ADR 0032)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/DataStoreScopesTest.kt`: `a store reopens on the same file while the last scope's write was in flight (ADR 0032)`, `closing a scope whose work never ends fails instead of hanging (ADR 0032)`, `a block that fails keeps its own failure when its scope also fails to close (ADR 0032)`
- `detekt-rules/src/test/kotlin/com/theoriacodex/detekt/DefaultedCoroutineScopeRuleTest.kt`: `a CoroutineScope parameter with a default is reported (ADR 0032)`, `a scope without a default, another defaulted type, or a type alias is not reported (ADR 0032)`, `the Theoria rule set runs the defaulted scope rule (ADR 0032)`
- `config/detekt/detekt.yml`: `DefaultedCoroutineScopeRule: active: true includes: ['**/core-data/src/main/**']`
- Left to prose: that the startup gate awaits every store that can fail, and that callers never cancel a store scope by hand, are wiring and usage rules in `:app` and test helpers that a text rule could only guess at, and the gate has no host test seam, so both are unguarded; a schema bump making older builds refuse the file is the point of the version, not a check. The Detekt rule `DefaultedCoroutineScopeRule` reports a defaulted `CoroutineScope` parameter in `core-data` main sources; it resolves the type through imports, so a scope parameter written through a type alias is not reported.
