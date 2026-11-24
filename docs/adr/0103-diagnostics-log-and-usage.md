# 0103. The diagnostics log is redacted, bounded and written off the failing thread; data-usage counting exists only in debug builds

Status: Accepted

## Context

Failures are reported from the UI thread and from threads that are about to die, and the log is shared by the user on request. Counting traffic per HTTP client is only needed for a live measurement session.

## Decision

- `DiagnosticLogStore` redacts text before it reaches disk and keeps both files bounded, so it can stay on permanently. Events are appended on one background thread; during a flood the pending queue is capped (`MAX_PENDING_EVENTS`) and newer lines are dropped, because the earlier lines already show what went wrong.
- `writeCrash` runs on the crashing thread and blocks, since the process is about to die and nothing can be queued. `installCrashCapture` keeps the stack, then hands over to the previous handler so Android still ends the process as usual.
- `readEvents` and `readCrashes` run on the writer thread, so they see every event recorded before the call and `clear` cannot delete a file partway through a read.
- `DiagnosticReporter`'s system log section reads only the app's own logcat lines (Android shows apps only their own), so it needs no permission and cannot expose other apps. Every part of the report passes through redaction.
- `NetworkUsage` counts only in debug builds: `enabled` is a build constant, so release builds install no hooks and R8 removes it. It counts per client (`Stack`) with a network interceptor that sees redirects and retries, counts bodies as read and before gzip decoding, and reports the app's total traffic (uid bytes including TLS and headers) so the unread remainder is visible.
- `installOffMainChecker` runs once, in the main process only, next to the diagnostics log; no other process calls a source adapter. `installDebugStrictMode` (debug only) extends the thread's existing policy so Android's network-on-main protection stays; StrictMode sees disk access on the main thread, not the main thread waiting for another thread's disk access. Violations are counted per topmost app frame's outer class and reported once per distinct frame.

## Consequences

The log is safe to attach to a report and cannot grow or stall the app. A crash report is written synchronously, so it is the one path that can delay process death.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/DiagnosticLogStore.kt`: `DiagnosticLogStore`, `writeCrash`, `readEvents`, `readCrashes`, `installCrashCapture`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/DiagnosticReporter.kt`: `DiagnosticReporter`, `systemLog`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/NetworkUsage.kt`: `NetworkUsage`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/ThreadDiagnostics.kt`: `installOffMainChecker`, `installDebugStrictMode`, `strictModeViolations`
