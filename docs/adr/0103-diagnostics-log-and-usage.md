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
- Debug builds trace feed autoplay stalls into the log (area `autoplay`): a video card that has been on screen in the focused window for `FEED_AUTOPLAY_STALL_MS` without a feed player writes one line with its post id and every autoplay gate (visibility, lifecycle, video link, playback failure, lease wanted, lease held), another whenever a gate changes, and one when it clears or leaves. Cards that play write nothing, so a rare cold-start stall survives until the log is read. No URLs are written and release builds create no probe (`debugDiagnosticsEnabled` is a build constant).
- `installOffMainChecker` runs once, in the main process only, next to the diagnostics log; no other process calls a source adapter. `installDebugStrictMode` (debug only) extends the thread's existing policy so Android's network-on-main protection stays; StrictMode sees disk access on the main thread, not the main thread waiting for another thread's disk access. Violations are counted per topmost app frame's outer class and reported once per distinct frame.

## Consequences

The log is safe to attach to a report and cannot grow or stall the app. A crash report is written synchronously, so it is the one path that can delay process death.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/DiagnosticLogStore.kt`: `DiagnosticLogStore`, `writeCrash`, `readEvents`, `readCrashes`, `installCrashCapture`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/DiagnosticReporter.kt`: `DiagnosticReporter`, `readSystemLog`, `logcat`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/NetworkUsage.kt`: `NetworkUsage`
- `media-kit/src/main/kotlin/com/theoriacodex/app/diagnostics/ThreadDiagnostics.kt`: `installOffMainChecker`, `installDebugStrictMode`, `strictModeViolations`, `debugDiagnosticsEnabled`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/FeedAutoplayTrace.kt`: `FeedAutoplayStallTrace`, `FeedAutoplayProbe`, `FeedAutoplayTraceEffect`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/diagnostics/DiagnosticLogStoreTest.kt`: `events reach disk redacted and survive a restart (ADR 0002, 0103)`, `the log stays bounded (ADR 0103)`, `only the newest crashes are kept and clear removes everything (ADR 0103)`, `a flood past the pending cap keeps the earliest lines and drops the newest, off the caller's thread (ADR 0103)`, `a read waits for every event recorded before it (ADR 0103)`, `a crash is on disk before the previous handler ends the process (ADR 0103)`, `a crash is written when another thread creates the folder first (ADR 0103)`, `a crash reaches disk redacted (ADR 0103)`, `a crash that cannot be written still reaches the previous handler (ADR 0103)`, `clear waits for events recorded before it and removes them (ADR 0103)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/diagnostics/DiagnosticReporterTest.kt`: `every section of the report passes through redaction (ADR 0103)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/search/FeedAutoplayTraceTest.kt`: `a video card without a player is written once after the stall window, again when a gate changes, and when it clears (ADR 0103)`, `cards that are playing, off screen, behind another window or still images write nothing (ADR 0103)`
- `app/src/test/java/com/theoriacodex/app/diagnostics/NetworkUsageTest.kt`: `a redirect counts as its own request and a body counts as it arrived, before gzip decoding (ADR 0103)`, `a download abandoned midway counts only what was read (ADR 0103)`
- `scripts/tests/test_check_architecture.py`: `test_reports_a_manifest_that_asks_to_read_other_apps_logs_adr_0103`
- Left to prose: that Android shows an app only its own logcat lines is platform behaviour, and the manifest rule cannot see a permission merged in from a dependency's manifest; that release builds install no `NetworkUsage` hooks and R8 removes it shows only in a release build, a retried request being counted again is not yet guarded, testable in app, and the app's total traffic figure needs the device's real traffic accounting; that `installOffMainChecker` runs once and only in the main process is not yet guarded, testable in app; counting StrictMode violations by the reading code's outer class is shown only by the app's device test on an emulator; that only debug builds install the StrictMode checks or create autoplay probes shows only in a release build; that the probe's gates match what the card actually did shows only on a device; that the debug policy adds disk checks while keeping the thread's existing protections shows only on a device, because Android offers no public way to read a thread policy's checks; that the report's default system log reader runs the device's logcat binary shows only on a device.
