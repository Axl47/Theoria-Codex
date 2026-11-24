# 0073. Main-thread work is reported once per call site and counted without limit

Status: Accepted

## Context
Work that must run off the main thread can be called from many places, and one old offender must not hide new ones or cost a stack capture on every repeat.

## Decision
- `ViolationLedger` counts every violation per kind and reports the first occurrence at each distinct call site, for the first `siteLimit` sites in the process, then one notice that the limit was reached. Past the limit `record` no longer asks for the site, so no stack is captured.
- `MainThreadWorkChecker` does nothing off the main thread and never throws. Call sites are the label plus the top frames below `OffMainWork.note`.
- In minified builds frames are renamed and `note` may be inlined away, so distinct callers can merge; a report there means a violation exists under that label, to be reproduced in a debug build.

## Consequences
Repeating offenders stay cheap. Release-build reports are coarser than debug ones.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/diagnostics/MainThreadWorkChecker.kt`: `MainThreadWorkChecker`
- `app-logic/src/main/kotlin/com/theoriacodex/app/diagnostics/ViolationLedger.kt`: `ViolationLedger`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/diagnostics/ViolationLedgerTest.kt`: `each site is reported once and every occurrence is counted (ADR 0073)`, `past the limit it notes the limit once, stops asking for sites and keeps counting (ADR 0073)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/diagnostics/MainThreadWorkCheckerTest.kt`: `off the main thread nothing is counted or reported (ADR 0073)`, `a failing report never reaches the caller and the run is still counted (ADR 0073)`, `each call site is reported once with frames starting at its caller (ADR 0073)`, `one call path noting two labels is reported once for each label (ADR 0073)`
- Left to prose: how R8 renames frames and inlines `note` in minified builds is unguarded, because host tests do not run release builds.
