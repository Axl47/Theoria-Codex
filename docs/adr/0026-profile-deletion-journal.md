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

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileDeletionServiceTest.kt`: `a caller that leaves after the intent is admitted still removes the profile (ADR 0026)`, `a journal from a newer or damaged writer is kept and never acted on (ADR 0026)`, `capacity preflight and invalid durable intent fail before library mutation (ADR 0026)`, `retired profile checks execute inside Settings writes without affecting another profile (ADR 0026)`, `a journal the Gson store wrote resumes the same removal (ADR 0026)`, `the profile removal journal golden pins every stored key (ADR 0026)`, `a startup recovery whose caller leaves partway still finishes the removal (ADR 0026)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `a profile deleted this session is usable again once restored, and others stay retired (ADR 0025, 0026)`
- Left to prose: that each new profile-owned write consults `ProfileLibraryWriteGuard` is a rule for whoever adds a write; a text rule over Room repositories would be guesswork.
