# 0026. Profile deletion runs from a fail-closed durable intent and blocks writes to retired profiles

Status: Accepted

## Context

Deleting a profile spans one atomic Room library deletion and a settings removal. A crash or caller departure between them must neither skip the second half nor guess at what happened.

## Decision

- A small journal bridges the two steps. Once the intent is admitted, the work runs under `NonCancellable` so the caller leaving cannot skip finishing or the guard.
- Intent reads fail closed: both keys are required and size is bounded, so corruption never erases a partly completed operation.
- `ProfileLibraryWriteGuard` keeps retired identities blocked until process exit, or until a restore commits that brings the profile back (`reinstate`).

## Consequences

New profile-owned writes must consult the guard. Changing the journal shape needs a read path for old journals.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileDeletionService.kt`: `ProfileDeletionService`, `readPending`, `ProfileDeletionJournal`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileLibraryWriteGuard.kt`: `ProfileLibraryWriteGuard`, `reinstate`
