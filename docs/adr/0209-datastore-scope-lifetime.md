# 0209. A DataStore's scope is closed by cancelling it and waiting for its release, within a bound

Status: Accepted

## Context

DataStore allows one active store per file and releases that claim only when the store's scope job completes. Completion can trail `cancel()` while a write is still in flight, so code that cancels a scope and then reopens the same file, or deletes its directory, races the old store. The R8 release acceptance hit this as `IllegalStateException: There are multiple DataStores active for the same file`; a host loop reopening the settings store after cancel alone failed 61 times in 1,000 and never after closing through this helper. The repositories take a raw `CoroutineScope` and have no `close()`, so each caller had written its own close, and some only cancelled.

## Decision

- Every open-use-close of a DataStore repository goes through `DataStoreScopes.kt`: `withDataStoreScope { scope -> ... }` for a bounded use, or `newDataStoreScope()` with `closeDataStores()` when a scope outlives one block (fixture containers, tests that hold scopes across steps).
- Closing registers a completion handler on the scope's job, cancels the job, and waits for that handler. `Job.join()` is not enough: it returns once the job's state reads completed, while the job's completion handlers, DataStore's file release among them, still run afterwards on the thread that completed it. Handlers run in registration order and DataStore registers its release when the store is created, so the helper's handler fires only after the release has returned.
- The wait runs on `Dispatchers.IO` under `NonCancellable`, so it finishes even when the caller is being cancelled, and so a `runTest` caller's virtual clock cannot expire it early.
- The wait is bounded at `DATASTORE_CLOSE_TIMEOUT` (5 s). A store that does not release in time is an error, not a silent pass, since the next open of that file would fail anyway with a less useful message. When the block already failed, the timeout is attached to that failure as suppressed so the original cause stays first.
- A store scope hosts only stores. The repositories take their scope with no default, so every caller names the owner that closes it; a container whose scope also runs other work gives its stores a separate `newDataStoreScope()` and closes that after the other work has stopped.
- The application's process-lifetime store scope is never closed and stays outside this rule.

## Consequences

A new harness that reopens a store is correct by using the helper instead of copying a close. `DataStoreScopesTest` reopens the same file 300 times, each while the previous scope's write is in flight, as the regression check; with cancel alone it collides on about a third of reopens. Giving the repositories their own `suspend fun close()` would make the scope an implementation detail and retire these helpers.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DataStoreScopes.kt`: `withDataStoreScope`, `newDataStoreScope`, `closeDataStores`
- `app/src/fixtures/java/com/theoriacodex/app/fixtures/JourneyAppContainer.kt`: `shutdown`
