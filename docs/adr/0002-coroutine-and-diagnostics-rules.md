# 0002. Shared requests, cancellation, diagnostics and off-main checks follow fixed rules

Status: Accepted

## Context

Several modules share request coalescing, failure logging and main-thread checks. Each has a failure mode that is easy to reintroduce: cancelling other callers, swallowing cancellation, logging browsing content, or logging that itself throws.

## Decision

- `runCatchingPreservingCancellation` is used at boundaries that may run suspend work so cancellation stays control flow; parsing and value conversion may use plain `runCatching`.
- `SharedFlights` shares only overlapping requests per key; a request started after another finished runs again, so callers needing a fresh answer do not use it. The request runs on the first caller's dispatcher but under its own job, and is cancelled only when every waiter has left.
- `Diagnostics.report` never throws, so a broken log cannot become a second failure. Messages name what failed, never a query, title or other browsing content.
- `DiagnosticRedaction` strips identifying text before storage or sharing. Addresses are redacted first (paths and queries hold post ids, tags and keys) but the host is kept to locate a failure. Extension source ids are as long as tokens but are exempt from opaque-value redaction because they name which source failed.
- `OffMainWork.note` is called where the work executes (first line of a parse helper), never at the start of a suspend function that callers may correctly invoke on the main thread. Labels are constants, never data. `SELF_TEST` is the permanent control a device test notes on the main thread to prove the app's checker reports.

## Consequences

Diagnostics stay safe to share. Redaction patterns must be revisited when new id formats appear. Moving `note` calls into suspend entry points would produce false main-thread reports.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/coroutines/CoroutineResults.kt`: `runCatchingPreservingCancellation`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/coroutines/SharedFlights.kt`: `SharedFlights`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/diagnostics/Diagnostics.kt`: `Diagnostics`, `DiagnosticRedaction`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/diagnostics/OffMainWork.kt`: `OffMainWork`
