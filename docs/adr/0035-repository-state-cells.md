# 0035. Repository rules run over a StateCell so in-memory and durable backings share one code path

Status: Accepted

## Context

Repositories need an in-memory default for tests and backups and a durable store for the app. Separate implementations of the same rules drift apart.

## Decision

- A repository holds its policy over a `StateCell`; the backings are `JsonDataStore`, `MemoryStateCell` and `FileStateCell`. `updateReturning` applies one transform at a time, and a value equal to the current one writes nothing.
- List rules, such as those for site profiles, are pure functions in a policy object (`SiteProfileListPolicy`) applied by `CellSiteProfileRepository` for both backings.
- `FileStateCell` writes each change before observers see it, so a failed write leaves the value as it was.
- `writeBehind` wraps a durable cell for stores written on every view (ADR 0215): observers see a change at once and the durable write follows, so a failed write keeps the new value in memory and retries.
- Codex and Recents behavior is pinned by `LibraryRepositoryContract`: the in-memory fakes run it in core-data and Room runs it in core-data-android, so the fakes cannot drift from the durable store.

## Consequences

A new rule belongs in the shared policy, not in a backing. A new backing must pass the contract tests.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/StateCell.kt`: `StateCell`, `MemoryStateCell`, `FileStateCell`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SiteProfileListPolicy.kt`: `SiteProfileListPolicy`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SiteProfileRepository.kt`: `CellSiteProfileRepository`
- `core-data/src/testFixtures/kotlin/com/theoriacodex/data/repository/LibraryRepositoryContract.kt`: `LibraryRepositoryContract`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/storage/StateCellTest.kt`: `an unchanged value is not written (ADR 0035)`, `a failed or cancelled write keeps the value and preserves the failure (ADR 0035)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `concurrent settings mutations are serialized without lost profile state (ADR 0035)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SiteProfileRecordsTest.kt`: `in-memory repository enforces identity, the site limit and restore semantics (ADR 0035)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreSiteProfileRepositoryTest.kt`: `mutations follow the shared list rules (ADR 0035)`
- Left to prose: the fakes stay in step with Room because `InMemoryLibraryContractTest` and `RoomLibraryContractTest` both extend `LibraryRepositoryContract`, a test-class declaration no guard line can pin; that a new backing passes the contract and a new rule goes in the shared policy are rules for authors.
