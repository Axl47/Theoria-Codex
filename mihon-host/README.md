# mihon-host (spike)

A debug-only experiment: load a [Mihon](https://github.com/mihonapp/mihon) manga extension APK and browse it as a
Theoria source. It exists to answer one question: how much of Mihon's run-time must Theoria provide before the
extension ecosystem works here? The answer and the measurements are below. Release builds contain none of this.

## How it works

A Mihon extension is an APK that is never installed. The host app reads its manifest, loads its classes, and calls
them through a small API (`eu.kanade.tachiyomi.*`, plus a service locator, `uy.kohesive.injekt.*`). The extension is
compiled against those names, so the host must provide classes with exactly those packages, names and signatures.

| Piece | Where | What it does |
| --- | --- | --- |
| Host API | `src/main/kotlin/eu/kanade/tachiyomi`, `uy/kohesive/injekt` | The classes extensions link against, trimmed from Mihon (Apache 2.0, see `LICENSE-APACHE-2.0.txt`). Only the coroutine API; no RxJava. |
| `ExtensionLoader` | `com.theoriacodex.mihonhost` | Checks the APK's manifest, copies it to a private read-only file (Android refuses writable code), loads it and creates its sources. |
| `MihonHost` | same | Gives extensions what they ask the service locator for: the application, a `Json`, and an HTTP client. |
| `MihonSourceAdapter` | same | Presents one extension source as a Theoria `SourceAdapter`. A series becomes one post; opening it shows the pages of the series' first chapter. |
| `ExtensionImages` | same | Fetches covers and pages through the extension's own HTTP client. Each address handed to the app ends in `#mihon=<source id>`; an interceptor on the app's media client answers those. |
| App seam | `app/.../di/ExtensionSources.kt`, `app/src/debug/.../MihonExtensionSourceHost.kt` | Debug builds find the host through `ServiceLoader` and feed its sources into the same registry Custom Sites use. |

## Try it

Use an emulator. The extension's code runs inside the app with the app's permissions, so load only extensions you
trust, and follow `AGENTS.md` ("Personal-device Safety") before installing anything.

Without the app, on any extension APK:

```sh
./gradlew :mihon-host:assembleDebugAndroidTest
APK=mihon-host/build/outputs/apk/androidTest/debug/mihon-host-debug-androidTest.apk
python3 scripts/verify_device_apk.py $APK --package com.theoriacodex.mihonhost.test
adb -s <emulator> install -r -t $APK
adb -s <emulator> shell mkdir -p /sdcard/Android/data/com.theoriacodex.mihonhost.test/files
adb -s <emulator> push extension.apk /sdcard/Android/data/com.theoriacodex.mihonhost.test/files/
adb -s <emulator> shell am instrument -w -r -e extensionApk extension.apk \
  -e class com.theoriacodex.mihonhost.ExtensionHarnessTest \
  com.theoriacodex.mihonhost.test/androidx.test.runner.AndroidJUnitRunner
```

It lists popular series, opens one, fetches a cover and a page, and prints counts, timings and request counts as
`harness=` lines. It never prints or saves images.

In the debug app:

```sh
adb -s <emulator> shell mkdir -p /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions
adb -s <emulator> push extension.apk /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/
```

Restart the app. The source appears in Search's source picker under Custom. Send a search with no tags for the
source's popular series, or type words for a text search. `ExtensionSourceLiveTest` (in `app/src/androidTest`) does
the same through the real UI: `-e extensionSourceLive true -e class com.theoriacodex.app.live.ExtensionSourceLiveTest`
on `com.theoriacodex.debug.test`.

## What was measured (November 2026)

The extension index used listed 1,711 extensions (2,742 sources), every one built against extension API 1.6.

**The host surface is small.** MangaDex 1.6.4 is a 103 KB APK. Across a random sample of 300 extensions:

- None references RxJava. The whole sample uses the coroutine API, so the host needs no Rx.
- They use about 29 host classes plus 4 service-locator classes. The locator is asked for only four types: `Json`
  (257 extensions), `Application` (207), `ProtoBuf` (3) and `NetworkHelper` (1).
- None ships native code. The median APK is 78 KB.
- Checked symbol by symbol against the built debug app: 205 (68%) link completely, 89 (30%) lack only
  `androidx.preference` (their settings screen), and 6 (2%) need something this host lacks (protobuf, a JavaScript
  engine, an image decoder).
- Mihon ships the same OkHttp (5.5.1) and kotlinx.serialization (1.13.0) as Theoria.
- Mihon's `source-api` changed in 14 commits in the last year, three of them the move to API 1.6.

**Two requirements were not visible in the API.** Both were found by running MangaDex:

- Every extension carries a shared library that rebuilds the host's HTTP client and refuses to run unless it finds
  interceptors named `UncaughtExceptionInterceptor`, `UserAgentInterceptor` and `CloudflareInterceptor` in it
  (300 of 300 sampled). The host must keep classes with those names.
- Most extensions decode JSON from the response stream with `kotlinx-serialization-json-okio` (246 of 300), which
  Theoria did not ship.

**Images must go through the extension.** MangaDex answers a cover request from any other HTTP client with a bot
check, and builds each page's address per request from a short-lived image server. Mihon fetches both through the
source's client; so does this host.

**Timings on an emulator (MangaDex).** Load the APK: 45 ms. Popular listing: 1 request, about 1 s. Open a series
(details, chapter list, page list): 4 requests, about 1.5 s alone. In the app, opening a series right after a listing
took 7 to 10 s: the 20 covers go through the same rate-limited client, and the Viewer also resolves the next post.

## What is missing

This is what a real feature would have to add; none of it is needed to answer the spike's question.

- **A series model.** A series here is one post showing its first chapter. No chapter list, no reading progress, no
  library, no updates.
- **Search filters.** Each extension defines its own filters (genre, status, sort). Only popular, latest and free
  text are used. Posts carry no tags.
- **Extension settings.** `androidx.preference` is absent, so the 30% of extensions with a settings screen fail to
  load, and the rest run on their defaults. MangaDex writes its settings into the app's default preferences file.
- **Cloudflare.** `CloudflareInterceptor` is an empty stand-in. A site behind a browser challenge answers 403.
- **A JavaScript engine, protobuf and extra image decoders**, used by about 2% of extensions.
- **Installing, updating and trusting extensions.** There is no repository browser, no update check, and no signer
  check beyond logging the fingerprint. Extension code is unsandboxed: it can read the app's files and cookies.
- **Release builds.** R8 would rename or remove the libraries extensions link against (OkHttp, Okio, kotlinx,
  Jsoup). Shipping this needs keep rules for their public API and a size measurement.
- **Saved posts.** A page address can expire within minutes (MangaDex). A post reopened later from history keeps its
  old page addresses; it would have to be resolved again.
- **The source logo.** The picker's icon is the site's favicon, fetched by the app's own client; MangaDex bot-checks
  it, so the text logo shows instead.
- **Cancellation.** An image fetch that the app cancels still runs to completion on the extension's client.

## Removing it

Delete `mihon-host/`, `app/src/debug/java/com/theoriacodex/app/extensions/`, the `META-INF/services` file under
`app/src/debug/resources/`, and `ExtensionSourceLiveTest`. Then remove the module's lines from
`settings.gradle.kts`, `app/build.gradle.kts`, the root `build.gradle.kts`, `scripts/check.sh`,
`scripts/verify_device_apk.py`, `config/quality/hotspot-budgets.json` and its Detekt baseline, and the three library
aliases in `gradle/libs.versions.toml`. The seam in `:app` and `:media-kit` (`ExtensionSourceHost`, the extra flow in
`CustomSourceRuntime`, `AppHttpClients`' `extensionMedia`) does nothing without a host and can stay or go.
