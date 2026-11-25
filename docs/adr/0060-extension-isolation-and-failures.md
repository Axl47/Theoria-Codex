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

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionCallsTest.kt`: `an extension call runs off the caller's thread (ADR 0060)`, `a failure keeps the extension's own message, never its class name, and an adapter's exception passes unchanged (ADR 0060)`, `only a virtual machine error escapes an extension, and every other throwable is its failure (ADR 0060)`, `only errors reach the diagnostics log, and a missing host class says so (ADR 0060)`, `time-limited extension work gets 32 threads of its own beside the IO pool (ADR 0060)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionLoaderTest.kt`: `a virtual machine error while an extension is created ends the load, and any other throwable rejects only that extension (ADR 0060)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/MihonSeriesSourceTest.kt`: `an extension's own cancellation is its failure, and the caller's passes through (ADR 0060)`, `failures map to the reasons built-in sources report (ADR 0060)`, `a source's filter list is asked like its other calls, so its failure is the source's (ADR 0060)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSourceTest.kt`: `a server that ignores cancellation delays neither the answer nor a caller that stops waiting (ADR 0055, 0060)`, `a virtual machine error from a server's lookup is not taken for that server's failure (ADR 0060)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/FilePreferencesTest.kt`: `aListenersVirtualMachineErrorIsNotReportedAsItsFailure_adr_0060`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionRegistryTest.kt`: `an extension that hangs or fails is named and the others still load (ADR 0060)`, `a virtual machine error while an extension loads ends the process, also after its time ran out, and other failures are load problems (ADR 0060)`
- `extension-host/src/test/kotlin/eu/kanade/tachiyomi/network/interceptor/HostInterceptorsTest.kt`: `a non-IO exception later in the chain is wrapped in IOException (ADR 0060)`, `an IOException passes through as it is (ADR 0060)`
- Left to prose: the registry handing extensions `HostServices`' own 32-thread pool rather than the IO pool is not observable without exposing the registry's services; the loading thread being a daemon, 32 covering the largest measured fan-out, and a `LinkageError` meaning missing host API are rationale; the Suppress annotations on broad catches are tied to these rules by review.
