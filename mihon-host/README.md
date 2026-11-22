# mihon-host

Runs [Mihon](https://github.com/mihonapp/mihon) manga extensions and presents their sources as `SeriesSource`s, which
the Series tab browses and the Viewer reads chapter by chapter. It finds the extensions Mihon installed on the device,
and runs one only after the user turns it on. Only debug builds include it; release builds contain none of this.

It began as a spike asking how much of Mihon's run-time Theoria must provide before the extension ecosystem works
here. That verdict and its measurements are kept below; the Series feature is built on top of it.

## Verdict (the spike)

Hosting the ecosystem is feasible, and the host is small: about 1,000 lines, of which 450 are Theoria's own and the
rest is Mihon's API trimmed to what extensions call. An unmodified MangaDex extension lists series, opens one and
shows its pages in the real Viewer. Of 10 other extensions tried, 7 listed, opened and fetched a page unchanged.

Three costs decided whether to build it. The spike solved none; settings and trust have since been built (see "How it
works"), and release size is still open:

- **Settings.** 30% of extensions have a settings screen built on `androidx.preference`, which Theoria does not
  ship. They cannot load until the host provides it, and some need their settings to work at all (a site address, a
  login).
- **Release size.** Extensions call OkHttp, Okio, kotlinx and Jsoup by name, so R8 must keep those libraries whole.
  Measured on the release build: 10.3 MB grows to 15.0 MB (+46%), or 17.8 MB (+73%) if the Kotlin standard library
  is kept whole too. About 3 MB of that is the zstd decoder Mihon's HTTP client includes.
- **Trust.** Extension code runs with the app's permissions and can read its data, so none runs until the user turns
that extension on in the Extensions page. What is stored is the extension's package name with the SHA-256 of the
signing certificate it had then (`ExtensionChoices.trusted`, in the settings store): an update signed with the same
key stays on, a copy signed with another key is off, and so is every other extension, whoever signed it, since
nearly all Mihon extensions share one key. A file in the debug folder is its own extension, named by its file name and the package inside it, so another
extension put there under the same name is off and cannot be turned on by an answer given for the first.
An APK with no readable signing certificate, or with several signers, can never be turned on. Until an extension is
on, its name, version and fingerprint come from the package manager alone. The question asked before turning one on
shows the fingerprint, and only that fingerprint is trusted: a file replaced while the question was open is asked
about again. Loaded code is kept per listed file, signer, version, length and write time included, so a file replaced
in place loads anew, from a private copy of its own, and never runs under another file's signer or name. Trust is
never written to or read from a profile backup.

**Changes on the device.** Every source lookup first compares a cheap stamp of the device's packages and the folder's
files; an extension installed, updated or removed since then is listed again and only what changed is loaded. Every
extension image request makes the same check, so a source removed or turned off stops fetching images. Debug
builds also listen for package changes while the app runs, so the Series tab follows without a restart.

**Pages.** A chapter's pages are handed out without further requests. Where a source names each image on a page of
its own, the address handed out is that page's, marked `#mihon-page=` with the source, the page's place and its
chapter, and the image's address is asked for when the image is requested, so one page failing fails only that
page. When that image does not arrive its address is asked for again next time, since such addresses expire; when
the page is no longer held, after a restart, the chapter's pages are listed again. Extension calls run off the main thread.

**Languages.** An extension can ship one source per language (MangaDex ships 62). The ones on by default are those
matching a device language, else English, else the first; the Extensions page switches each on and off.

**Settings.** An extension describes its settings by filling an `androidx.preference.PreferenceScreen`. The classes
of that name here are Theoria's own plain holders, not AndroidX: the host reads what the extension put in and the
Extensions page draws it with the app's settings rows. A survey of 300 extensions fixed the surface: `Preference`
(`key`, `title`, `summary`, `defaultValue`, `isEnabled`, `isVisible`, `onPreferenceChangeListener`),
`PreferenceScreen` (`addPreference`, `context`), `SwitchPreferenceCompat` and `CheckBoxPreference` (`summaryOn`,
`summaryOff`), `EditTextPreference` (`text`, `dialogTitle`, `dialogMessage`, `onBindEditTextListener`),
`ListPreference` and `MultiSelectListPreference` (`entries`, `entryValues`). Honored: defaults, summaries (a list's
`%s` names the chosen entry), a change listener returning false to refuse a value, enabled and visible. As in
AndroidX, a preference added to the screen stores its declared default when nothing is stored yet, and an
`EditTextPreference`'s `text` is the stored one, so the source's code reads the values its rows show.
`onBindEditTextListener` is held and never called, because there is no `EditText`: a source that used it to set a
numeric keyboard or validate while typing gets a plain text field. Each source reads its values from a preferences
file it asks for by Mihon's name, `source_<id>`; the host hands extensions an `Application` that stores every such
file as `mihon_<name>`, so extension files cannot be mistaken for the app's own.

| Piece | Where | What it does |
| --- | --- | --- |
| Host API | `src/main/kotlin/eu/kanade/tachiyomi`, `uy/kohesive/injekt` | The classes extensions link against, trimmed from Mihon (Apache 2.0, see `LICENSE-APACHE-2.0.txt`). Only the coroutine API; no RxJava. |
| `ExtensionPackages` | `com.theoriacodex.mihonhost` | Lists the extensions on the device without running them: installed packages with the extension feature, and APK files in the debug app's `mihon-extensions` folder. |
| `ExtensionRegistry` | same | The rule for what runs: loads the extensions that are on, on first use, each with 15 s to finish, and offers the sources of the languages that are on; only those sources fetch images. One that hangs or fails is left out and says why on the Extensions page. Settings are read only for the extension whose page is open. |
| `ExtensionLoader` | same | Loads an installed package's code from its own APK; a folder file is first copied to a private read-only file (Android refuses writable code) and the copy's signer checked again. |
| `SourceSettings`, `androidx/preference` | same | Reads a source's settings out of the holder classes and stores a changed value where the source reads it. |
| `MihonHost` | same | Gives extensions what they ask the service locator for: the application, a `Json`, and an HTTP client. |
| `MihonSeriesSource` | same | Presents one extension source as the app's `SeriesSource` (`core-domain`): popular and text search list series, a series has details and chapters, and a chapter gives its pages when it is opened. |
| `ExtensionImages` | same | Fetches covers and pages through the extension's own HTTP client. Each address handed to the app ends in `#mihon=<source id>`; an interceptor on the app's media client answers those. |
| App seam | `app/.../di/ExtensionSources.kt`, `app/src/debug/.../MihonExtensionSourceHost.kt` | Debug builds find the host through `ServiceLoader`. Nothing loads at startup: the extensions that are on load once, off the main thread, the first time the Series tab asks for sources or an image address ending in `#mihon=` is requested. The debug manifest holds `QUERY_ALL_PACKAGES`, as Mihon does: extension packages share only a declared feature, which a `<queries>` element cannot match. |
| Series | `feature-series`, `app/.../series/`, `core-data` `SeriesRepository` | The Series tab and series page, the Extensions page (also reached from Settings), the Viewer session over a series' chapters, and the `series` and `series_entries` tables that keep followed series, read state and progress. Pages are never stored. |

## Try it

Use an emulator. The extension's code runs inside the app with the app's permissions, so turn on only extensions you
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

It lists popular series, opens one's first chapter, fetches a cover and a page, and prints counts, timings and request counts as
`harness=` lines. It never prints or saves images.

In the debug app, install the extension the way Mihon does, as a package (for tests, a file pushed to
`/sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/` is found too):

```sh
adb -s <emulator> install -r extension.apk
```

Open the Series tab, go to Extensions and turn the extension on. The tab then browses its popular series, searches
every source that is on at once, and opens a series' page, from which a chapter opens in the Viewer. Two live tests
in `app/src/androidTest` do this through the real UI on `com.theoriacodex.debug.test`, with
`-e extensionSourceLive true`. Each method is its own run, because the next one checks what survived the app's
process restarting. `ExtensionsLiveTest` first, since it is what turns the extension on:

```sh
R=com.theoriacodex.debug.test/androidx.test.runner.AndroidJUnitRunner
run() { adb -s <emulator> shell am instrument -w -r -e extensionSourceLive true -e class com.theoriacodex.app.live.$1 $R; }
run ExtensionsLiveTest#anInstalledExtensionRunsOnlyOnceTurnedOn
run ExtensionsLiveTest#aChangedSettingSurvivesARestart
run ExtensionSourceLiveTest#aSeriesIsBrowsedReadResumedAndFollowed
run ExtensionSourceLiveTest#aFollowedSeriesIsOnTheShelfAfterARestart
```

`ExtensionsLiveTest#anUninstalledExtensionLeavesTheSeriesTab` removes the package itself while the app runs, so it
goes after those. `ExtensionsLiveTest#aCopySignedWithAnotherKeyIsOff` runs after the same APK, signed with another key
(`apksigner sign` with a fresh keystore), is installed in its place.

Followed series are checked for new chapters, in the sources of extensions that are on, by pulling down on the Series
tab and by a periodic background job
(`SeriesUpdateJobService`, every 12 hours unless Settings > Check for new chapters says otherwise), scheduled only
while a series is followed. A chapter is new when its address was not stored before; each series that gained some
gets one local notification that opens its page. The same test class proves it, with the job run by hand in between:

```sh
run ExtensionSourceLiveTest#followingASeriesAsksToNotifyAndSchedulesTheCheck
run ExtensionSourceLiveTest#aFollowedSeriesForgetsItsNewestChapter
adb -s <emulator> shell dumpsys jobscheduler com.theoriacodex.debug | grep SeriesUpdateJobService
adb -s <emulator> shell cmd jobscheduler run -f com.theoriacodex.debug 3101
adb -s <emulator> shell dumpsys notification --noredact | grep -E "android.(title|text)="
run ExtensionSourceLiveTest#aNewChapterNotificationOpensItsSeries
run ExtensionSourceLiveTest#unfollowingTheLastSeriesCancelsTheCheck
```

## What was measured (November 2026)

The extension index used listed 1,711 extensions (2,742 sources), every one built against extension API 1.6.

**The host surface is small.** MangaDex 1.6.4 is a 103 KB APK. Across a random sample of 300 extensions:

- None references RxJava. The whole sample uses the coroutine API, so the host needs no Rx.
- They use about 29 host classes plus 4 service-locator classes. The locator is asked for only four types: `Json`
  (257 extensions), `Application` (207), `ProtoBuf` (3) and `NetworkHelper` (1).
- None ships native code. The median APK is 78 KB.
- Checked symbol by symbol against the built debug app: 205 (68%) link completely, 89 (30%) lack only
  `androidx.preference` (their settings screen, which the host now provides), and 6 (2%) need something this host lacks (protobuf, a JavaScript
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
took 4 to 12 s: the covers go through the same rate-limited client, and the Viewer also resolves the next post.

**Ten more extensions through the harness.** Seven listed, opened a series and fetched a page image. One more
opened a series after a host fix (its details page returns no title, which Mihon tolerates) but could not fetch a
page: the site's own image link was dead. One sits behind a browser challenge, which needs the missing Cloudflare support. One gets HTTP 403 for page
images through its own client while a plain request succeeds; whether Mihon gets the same was not checked.

**How the sample was measured.** The 300 were drawn at random from the index (seed 20261122). Each APK's references
to classes, methods and fields outside itself were resolved against the debug app's own code and the Android
framework. The scripts are not in the repository: they depend on the index, which is not linked here.

## What is missing

What the feature still lacks.

- **Updates in release builds.** The background check's service and its notification and boot permissions are
  declared in the debug manifest only, beside the host.
- **Backup.** Followed series and reading progress are not in profile backups.
- **Search filters.** Each extension defines its own filters (genre, status, sort). Only popular, latest and free
  text are used. Posts carry no tags.
- **Unloading.** Loaded code cannot be unloaded. Turning an extension off stops its sources and images at once; its
  code stays idle in memory until the app's process ends.
- **Release builds.** The host, the `QUERY_ALL_PACKAGES` permission and the Series tab are debug-only.
- **Cloudflare.** `CloudflareInterceptor` is an empty stand-in. A site behind a browser challenge answers 403.
- **A JavaScript engine, protobuf and extra image decoders**, used by about 2% of extensions.
- **Installing and updating extensions.** There is no repository browser and no update check.
- **Other extension API versions.** Only extension API 1.6 loads, which is all the index held.
- **A limit on calls.** Loading an extension and reading its settings have a time limit; a listing or page request
  that hangs inside extension code is bounded only by the HTTP client's timeouts.
- **Cancellation.** An image fetch that the app cancels still runs to completion on the extension's client.
- **Pages that share an image address.** A source whose pages name the same image address and differ only in the
  request's headers or body would show one image for all.

## Removing it

Delete `mihon-host/`, `app/src/debug/java/com/theoriacodex/app/extensions/`, the `META-INF/services` file under
`app/src/debug/resources/`, and `ExtensionSourceLiveTest`. Then remove the module's lines from
`settings.gradle.kts`, `app/build.gradle.kts`, the root `build.gradle.kts`, `scripts/check.sh`,
`scripts/verify_device_apk.py`, `config/quality/hotspot-budgets.json` and its Detekt baseline, and the three library
aliases in `gradle/libs.versions.toml`. The seam in `:app` and `:media-kit` (`ExtensionSourceHost`, `AppHttpClients`'
`extensionMedia`) does nothing without a host, and without one the Series tab is not shown; `feature-series` and the
series tables depend only on `SeriesSource`, not on this module.
