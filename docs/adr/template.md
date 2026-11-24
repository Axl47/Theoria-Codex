# NNNN. Title of the decision

Status: Accepted

## Context

What forces the decision: the constraint, measurement or failure that made the obvious approach wrong.

## Decision

What the code does, stated so it stays true while the code around it changes.

## Consequences

What this makes easier and harder, and what would have to change to revisit it.

## Applies to

- `module/src/main/kotlin/.../File.kt`: `Symbol`, `Owner.member`

## Guarded by

- `module/src/test/kotlin/.../FileTest.kt`: `the promise in plain words (ADR NNNN)`
- `module/src/main/kotlin/.../File.kt`: `internal class Symbol private constructor`
- `scripts/check_architecture.py`: `rule_function_name`
- Left to prose: the promise no machine can check, and why.
