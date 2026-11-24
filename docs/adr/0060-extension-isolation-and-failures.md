# 0060. Extension code runs off the caller's thread, and its failures become source failures without leaking detail

Status: Accepted

## Context

Extension code runs in the app's process with the app's permissions, is often invoked on the main thread, can block on the network, ignore cancellation, hang, or throw anything (including `NotImplementedError` stubs and `LinkageError` for missing host API). OkHttp reports only `IOException` to its caller and lets anything else end the process. Failure messages are the extension's own text and can hold addresses or titles.

## Decision

- `ExtensionCalls.calling` runs extension calls off the caller's thread. Failures are reported with the failure's own message, never its class name (screens would show it). A `SourceAdapterException` thrown by the adapter passes unchanged.
- Only the caller's own cancellation is rethrown. A `CancellationException` thrown by the extension (own timeout, cancelled future) is that extension's failure, or a waiting screen would stop with no error (`extensionFailure`, `attempt`, `calling`).
- Only `VirtualMachineError` ends the process (`endsTheProcess`); every other throwable, errors included, is the extension failing.
- `failingAsIo` turns anything thrown on an HTTP client thread into an `IOException`.
- `failure` returns the extension's own `error.message` inside the `SourceAdapterException`. Only `Error`s are also written to the `Diagnostics` log (`LinkageError` as "needs host API this module lacks", others as "failed"). An `HttpException` is NETWORK (never a missing key, because the app holds no key for extension sources) except 429, which is RATE_LIMITED; other `IOException`s are NETWORK; `LinkageError` and other `Error`s are UNKNOWN; any other exception is PARSE.
- Extension work with a time limit runs in `HostServices.detached` on 32 threads beside, not out of, the 64 IO threads, so extractors blocked on dead servers cannot starve downloads and the database. 32 covers the largest measured fan-out (18 servers plus their list). Work past its limit is cancelled and abandoned, never joined.
- Loading runs extension code (names, languages, ids, factories, settings screens) on a daemon thread with `limitMs`; a hung thread is abandoned and the failure names what was running.
- A missing host class or method surfaces as `LinkageError` and means the extension needs more host API than this module provides.

## Consequences

Loaded code cannot be sandboxed or unloaded; this contains failure, not malice. The Suppress annotations on broad catches are deliberate and tied to these rules.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCalls.kt`: `ExtensionCalls.calling`, `failure`, `endsTheProcess`, `extensionFailure`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionImages.kt`: `failingAsIo`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiStreams.kt`: `attempt`, `abandoning`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionKind.kt`: `HostServices.detached`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionRegistry.kt`: `limited`, `threads`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionLoader.kt`: `createSources`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiKind.kt`, `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/MihonKind.kt`: `hosted`
