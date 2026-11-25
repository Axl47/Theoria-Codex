# 0002. Shared requests, cancellation, diagnostics and off-main checks follow fixed rules

Status: Accepted

## Context

Several modules share request coalescing, failure logging and main-thread checks. Each has a failure mode that is easy to reintroduce: cancelling other callers, swallowing cancellation, logging browsing content, or logging that itself throws.

## Decision

- `runCatchingPreservingCancellation` is used at boundaries that may run suspend work so cancellation stays control flow; parsing and value conversion may use plain `runCatching`.
- Where a timeout or a dependency's failure should become a message or a fallback, the call goes through `runCatchingUnlessCallerCancelled` rather than catching `TimeoutCancellationException` by hand: cancellation is rethrown only when the caller itself was cancelled, so a dependency that throws `CancellationException` while the caller is still running fails the request instead of leaving its screen loading, and `Error`s propagate. Provider autocomplete is the exception: an adapter's `CancellationException` means the scope changed under it, and the search coordinator's callers rely on it arriving as cancellation, so autocomplete keeps catching only its own timeout.
- `SharedFlights` shares only overlapping requests per key; a request started after another finished runs again, so callers needing a fresh answer do not use it. The request runs on the first caller's dispatcher but under its own job, and is cancelled only when every waiter has left.
- `Diagnostics.report` never throws, so a broken log cannot become a second failure. Messages name what failed, never a query, title or other browsing content.
- `DiagnosticRedaction` strips identifying text before storage or sharing. Addresses are redacted first (paths and queries hold post ids, tags and keys) but the host is kept to locate a failure. Extension source ids are as long as tokens but are exempt from opaque-value redaction because they name which source failed.
- `OffMainWork.note` is called where the work executes (first line of a parse helper), never at the start of a suspend function that callers may correctly invoke on the main thread. A lambda counts as the function's own body only when it runs in place (`let`, `run`, `also`, `apply`, `takeIf`, `takeUnless`, `with`) or as a `try` block; a note at the top of any other lambda (`launch`, `flow`, `lazy`, a callback) runs later, maybe on another thread, so it is reported. Labels are constants, never data. `SELF_TEST` is the permanent control a device test notes on the main thread to prove the app's checker reports.

## Consequences

Diagnostics stay safe to share. Redaction patterns must be revisited when new id formats appear. Moving `note` calls into suspend entry points would produce false main-thread reports.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/coroutines/CoroutineResults.kt`: `runCatchingPreservingCancellation`, `runCatchingUnlessCallerCancelled`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/related/RelatedPostsRouteController.kt`: `RelatedPostsRouteController`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchViewModel.kt`: `launchRequest`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScrubPreview.kt`: `ViewerScrubPreviews.follow`, `ViewerVideoScrubPreview`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/coroutines/SharedFlights.kt`: `SharedFlights`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/diagnostics/Diagnostics.kt`: `Diagnostics`, `DiagnosticRedaction`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/diagnostics/OffMainWork.kt`: `OffMainWork`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/coroutines/CoroutineResultsTest.kt`: `cancellation is rethrown unchanged (ADR 0002)`, `exception variant rethrows cancellation and captures exceptions (ADR 0002)`, `caller-only variant rethrows when the caller itself is cancelled (ADR 0002)`, `caller-only variant turns a cancelled dependency into a failure (ADR 0002)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/related/RelatedPostsRouteControllerTest.kt`: `a dependency's cancellation fails the shelf instead of leaving it loading (ADR 0002)`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/RequestOwnershipSearchViewModelTest.kt`: `a dependency's cancellation fails the search instead of leaving it loading (ADR 0002)`
- `config/detekt/detekt.yml`: `SuspendFunSwallowedCancellation: active: true`, `CancellationCatchRule: active: true`, `OffMainNoteRule: active: true`
- `detekt-rules/src/test/kotlin/com/theoriacodex/detekt/CancellationCatchRuleTest.kt`: `flags a rethrow before catching Throwable (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/coroutines/SharedFlightsTest.kt`: `the request runs on the first caller's dispatcher under its own job (ADR 0002)`, `overlapping callers share one request and a later caller starts a new one (ADR 0002)`, `a caller after every waiter left starts a new request while the old one winds down (ADR 0002)`, `the request is cancelled only once every waiter has left (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/diagnostics/DiagnosticsTest.kt`: `a sink that throws never breaks report (ADR 0002)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/diagnostics/DiagnosticRedactionTest.kt`: `addresses keep only their host (ADR 0002)`, `credentials are removed however they are written (ADR 0002)`, `personal values are removed and stack frames survive (ADR 0002)`, `extension source ids survive opaque-value redaction (ADR 0002)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/diagnostics/DiagnosticLogStoreTest.kt`: `events reach disk redacted and survive a restart (ADR 0002)`
- `detekt-rules/src/test/kotlin/com/theoriacodex/detekt/OffMainNoteRuleTest.kt`: `a note with a data label, in a suspend function, in a deferred lambda or after other work is reported (ADR 0002)`, `a constant note opening a non-suspend helper is not reported (ADR 0002)`, `the Theoria rule set runs the off-main note rule (ADR 0002)`
- Left to prose: the off-main rule does not follow an aliased import of `OffMainWork` or `note`; whether a diagnostic message names browsing content is a judgment at each call site, with redaction as the checked backstop. `SELF_TEST`'s control is the emulator-only `MainThreadWorkCheckerDeviceTest`, which is not linked until a device run can prove it.
