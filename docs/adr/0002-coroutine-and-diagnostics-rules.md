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

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/coroutines/CoroutineResultsTest.kt`: `cancellation is rethrown unchanged (ADR 0002)`, `exception variant rethrows cancellation and captures exceptions (ADR 0002)`
- `config/detekt/detekt.yml`: `SuspendFunSwallowedCancellation: active: true`, `CancellationCatchRule: active: true`
- `detekt-rules/src/test/kotlin/com/theoriacodex/detekt/CancellationCatchRuleTest.kt`: `flags a rethrow before catching Throwable (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/coroutines/SharedFlightsTest.kt`: `the request runs on the first caller's dispatcher under its own job (ADR 0002)`, `overlapping callers share one request and a later caller starts a new one (ADR 0002)`, `a caller after every waiter left starts a new request while the old one winds down (ADR 0002)`, `the request is cancelled only once every waiter has left (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/diagnostics/DiagnosticsTest.kt`: `a sink that throws never breaks report (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/diagnostics/DiagnosticRedactionTest.kt`: `addresses keep only their host (ADR 0002)`, `credentials are removed however they are written (ADR 0002)`, `personal values are removed and stack frames survive (ADR 0002)`, `extension source ids survive opaque-value redaction (ADR 0002)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/diagnostics/DiagnosticLogStoreTest.kt`: `events reach disk redacted and survive a restart (ADR 0002)`
- `scripts/tests/test_check_architecture.py`: `test_reports_off_main_notes_outside_the_start_of_a_parse_helper_adr_0002`
- Left to prose: whether a diagnostic message names browsing content is a judgment at each call site, with redaction as the checked backstop. `SELF_TEST`'s control is the emulator-only `MainThreadWorkCheckerDeviceTest`, which is not linked until a device run can prove it.
