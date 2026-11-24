# extension-host

Runs [Mihon](https://github.com/mihonapp/mihon) manga extensions and [Aniyomi](https://github.com/aniyomiorg/aniyomi)
anime extensions and presents their sources as `SeriesSource`s, which the Series tab browses and the Viewer reads
chapter by chapter or plays episode by episode. It finds the extensions Mihon or Aniyomi installed on the device,
installs and updates its own from the repos the user adds, and runs one only after the user turns it on. Every build includes it, and the Series tab is always shown.

It began as a spike asking how much of Mihon's run-time Theoria must provide before the extension ecosystem works
here. That verdict and its measurements are kept below; the Series feature is built on top of it.

## Verdict (the spike)

Hosting the ecosystem is feasible, and the host is small: about 1,000 lines at the time, of which 450 were Theoria's
own and the rest Mihon's API trimmed to what extensions call. Three costs decided whether to build it, and all three
have since been settled: settings (30% of extensions build a settings screen on `androidx.preference`, which Theoria
did not ship; see "Settings"), release size (see "Release builds") and trust (see "Trust"). The measurements behind
the verdict are under "What was measured".

## How it works

**Trust.** Extension code runs with the app's permissions and can read its data, so none runs until the user turns
that extension on in the Extensions page. What is stored is the extension's package name with the SHA-256 of the
signing certificate it had then (`ExtensionChoices.trusted`, in a file under `noBackupFilesDir`): an update signed with
the same key stays on, a copy signed with another key is off, and so is every other extension, whoever signed it, since
nearly all extensions of each kind share one key. A file in the debug folder is its own extension, named by its file
name and the package inside it, so another extension put there under the same name is off and cannot be turned on by
an answer given for the first. An APK with no readable signing certificate, or with several signers, can never be
turned on. Until an extension is on, its name, version and fingerprint come from the package manager alone. The
question asked before turning one on shows the fingerprint, and only that fingerprint is trusted: a file replaced while
the question was open is asked about again. Loaded code is kept per listed file, signer, version, length and write time
included, so a file replaced in place loads anew, from a private copy of its own, and never runs under another file's
signer or name. Trust is never written to or read from a profile backup.

**Repos.** The user adds the same repo addresses Mihon and Aniyomi take: a legacy `repo.json` and `index.min.json`
folder, or the newer gzipped protobuf index that legacy `repo.json` files name in `index_v2` (Keiyoushi's
`index.min.json` is now a stub, so its catalog is only there). Adding a repo pins the signing key it names; a repo that
later names another key offers nothing until it is added again. An extension is installed by downloading its APK and
checking that it is the package the index named, signed with the pinned key, no older than the copy on the device,
and that no copy on the device is signed with another key. It is kept as `noBackupFilesDir/extensions/<package>.apk`
and loaded like a debug-folder file, from a private read-only copy, so it is no Android package: no install dialog, no
developer verification, and another app cannot replace or remove it. A copy of its own stands in for an installed
package of the same name, unless the other app has updated that package past it with the same key. A fresh install turns the extension on, since installing it is the choice; an update keeps
the user's choice; removing it deletes the file and its trust. Repos live in `ExtensionChoices` beside trust and, like
it, are not in profile backups. Nothing checks for updates in the background: the Extensions page asks the repos each
time it opens.

**Changes on the device.** The app listens for package broadcasts while it runs, in every build, and debug builds also
watch the APK folder. Both start before the extensions first load. Each change lists the device again and loads only
what changed, so an extension installed, updated, removed, hidden or unhidden is picked up without a restart, and the
Series tab and the open Extensions page follow. Source lookups and image requests ask nothing of the device: they
read the sources last listed, so a source removed or turned off stops fetching images once that listing is done.

**Pages.** A chapter's pages are handed out without further requests. Where a source names each image on a page of
its own, the address handed out is that page's, tagged with the source, the page's place and its
chapter, and the image's address is asked for when the image is requested, so one page failing fails only that
page. When that image does not arrive its address is asked for again next time, since such addresses expire; when
the page is no longer held, after a restart, the chapter's pages are listed again. Extension calls run off the main thread.

**Languages.** An extension can ship one source per language (one ships 62). The ones on by default are those
matching a device language, else English, else the first; the Extensions page switches each on and off.

**Settings.** An extension describes its settings by filling an `androidx.preference.PreferenceScreen`. The classes
of that name here are Theoria's own plain holders, not AndroidX: the host reads what the extension put in and the
Extensions page draws it with the app's settings rows. Surveys of 300 manga and 168 anime extensions fixed the surface: `Preference`
(`key`, `title`, `summary`, `defaultValue`, `isEnabled`, `isVisible`, `onPreferenceChangeListener`),
`PreferenceScreen` (`addPreference`, `context`), `SwitchPreferenceCompat` and `CheckBoxPreference` (`summaryOn`,
`summaryOff`), `EditTextPreference` (`text`, `dialogTitle`, `dialogMessage`, `onBindEditTextListener`),
`ListPreference` and `MultiSelectListPreference` (`entries`, `entryValues`; a list also has `value` and
`findIndexOfValue`, which anime extensions' change listeners use). Honored: defaults, summaries (a list's
`%s` names the chosen entry), a change listener returning false to refuse a value, enabled and visible. As in
AndroidX, a preference added to the screen stores its declared default when nothing is stored yet, and an
`EditTextPreference`'s `text` is the stored one, so the source's code reads the values its rows show.
`onBindEditTextListener` is held and never called, because there is no `EditText`: a source that used it to set a
numeric keyboard or validate while typing gets a plain text field. Each source reads its values from a preferences
file it asks for by Mihon's name, `source_<id>` (Aniyomi uses the same name, so an anime and a manga source with the
same number would share one file; extension binaries fix the name); the host hands extensions an `Application` that stores every such
file as JSON under `noBackupFilesDir/mihon_preferences` (`FilePreferences`), so extension files cannot be mistaken
for the app's own.

**Browser checks.** The extensions' HTTP client keeps its cookies in the system WebView's store, for every host, as
Mihon's does: extensions are written against that, and many keep a session in cookies. It is not limited to the hosts
the app's own clients may send browser cookies to (`SiteCookieBridge`), since extension code runs in the app's process
and could read the store anyway. When Cloudflare answers with its challenge (403 or 503 marked `cf-mitigated: challenge`; a hard block or a
maintenance page passes through untouched), `CloudflareInterceptor` first lets the app's shared `ChallengePass` try it
out of sight, as Mihon does: it drops the clearance cookie the challenged request carried, loads the challenged address
in a WebView no one sees, with the request's agent and only the headers a browser would send itself (language,
referrer), waits up to 10 s for a new clearance cookie, stores it to disk, and sends the original request once more.
Requests to one host wait for a single pass, and at most two hidden browsers run at once across the extensions' client
and the app's own. A challenge still standing after that is recorded in the app's `HostOutageTracker` as a challenge,
with the refused address and agent, and the request fails with `HostUnavailableException`, so extensions never parse a
challenge page. The source then needs browser verification exactly like a built-in source or a Custom Site: the Series
tab and the series page say so with Verify in browser, which opens the app's one check page over the current screen,
with that agent and the host on screen. It closes itself once the extension's client gets through, and the screen that
asked loads again; if the browser got through and the client is still refused, the page says so and stays open. Hosts
other than the source's base address are traced back to the source that hit them, so a challenged image or API host
marks the right source. Cloudflare accepts a clearance only from the network address it was earned from, so the client
connects over the address family the WebView uses (`BrowserAddressDns`): where IPv6 and IPv4 leave the device from
different addresses, OkHttp's usual IPv6-first race would otherwise send the cookie from the other one and be refused.
While a host's challenge record lasts, no hidden browser is tried for it, so a screen of covers or the background
chapter check does not wait once per request; the record ends when it expires, when the check passes, or as soon as
the host answers a request successfully. Every wait ends when the call is cancelled. A missing or updating WebView, or
one whose renderer dies, counts as a failed check, never a crash.

**Android's backup and device transfer.** Neither carries extension data. The choices (which extensions are on, their
languages) and every extension's preferences, which can hold a server's address and password, are in
`noBackupFilesDir`, which Android never backs up or copies to a new phone, and are read from nowhere else. Debug
builds before the first release with extensions kept them in the settings store and in `shared_prefs/mihon_*.xml`;
the app deletes both at every start and imports neither, so an extension that was on in such a build is turned on
once more.

| Piece | Where | What it does |
| --- | --- | --- |
| Host API | `src/main/kotlin/eu/kanade/tachiyomi`, `uy/kohesive/injekt` | The classes extensions link against, trimmed from Mihon (Apache 2.0, see `LICENSE-APACHE-2.0.txt`). Only the coroutine API; no RxJava. |
| `ExtensionKind`, `HostedSource` | `com.theoriacodex.extensionhost` | The seam between an extension interface and the rest of the host. A kind says how its packages are recognized (manifest feature, source-class metadata, label prefix), which of them it refuses and why (interface version), and turns a created source class into `HostedSource`s: the `SeriesSource`, name, language, site address, settings and image fetching the host works with. Everything below works the same for every kind. |
| `MihonKind` | same | Mihon's manga interface, API 1.6, presented by `MihonSeriesSource`, with its covers and pages fetched by `MihonImages`. |
| `AniyomiKind` | same | Aniyomi's anime interface, versions 14 and 16, presented by `AniyomiSeriesSource` (see "Anime extensions"). Refuses other versions and extensions that stream by torrent, saying why on the Extensions page. |
| `ExtensionCalls`, `ExtensionFilters.kt` | same | What both adapters share: running an extension call off the caller's thread and reporting its failure the way built-in sources do, and the filter tree as `SeriesFilter`s. The status numbers are `core-domain`'s `extensionSeriesStatus`, shared with the backup import. |
| `ExtensionPackages` | same | Lists the extensions on the device without running them: those installed from repos into the app's storage, installed packages that declare a kind's feature (unless one from a repo stands in for it), reading each file again only once it changes and, in debug builds only, APK files in the app's `mihon-extensions` folder. |
| `RepoReader` | same | Reads a repo's name, key and extensions from any address users and backups give for one, in the legacy JSON or the newer protobuf format. Anime and manga protobuf stores number their fields differently; the anime layout is tried first. |
| `ExtensionCatalog` | same | The user's repos (`ExtensionRepos` in `core-domain`): adds and removes them, lists what they offer with how the device stands with each (available, installed, update, signed with another key), and installs, updates and removes extensions in the app's storage. |
| `ExtensionRegistry` | same | The rule for what runs: loads extensions that are on, on first use, each with 15 s to finish: a lookup of one source loads only the extension last seen offering it (`ExtensionChoices.owners`), and the whole list loads all of them. It offers the sources of the languages that are on; only those sources fetch images. One that hangs or fails is left out and says why on the Extensions page. Settings are read only for the extension whose page is open. |
| `ExtensionLoader` | same | Loads an installed package's code from its own APK; a repo or folder file is first copied to a private read-only file (Android refuses writable code) and the copy's signer checked again. |
| `SourceSettings`, `androidx/preference` | same | Reads a source's settings out of the holder classes and stores a changed value where the source reads it. |
| `ExtensionHost` | same | Gives extensions what they ask the service locator for: the application, a `Json`, and an HTTP client. The client shares the system WebView's cookies for every host, as Mihon's does (see "Browser checks"). |
| `MihonSeriesSource`, `AniyomiSeriesSource` | same | Presents one extension source as the app's `SeriesSource` (`core-domain`): popular, latest and search list series, a series has details and chapters (episodes), and a chapter gives its pages when it is opened (an episode its streams). A source's search filters become the app's `SeriesFilter`s (`MihonFilters.kt`, `AniyomiFilters.kt`); a search sets the values chosen onto a list the source makes afresh, matching each by place, kind and name, and leaves one that no longer lines up at the source's default; later pages of the same search reuse that list, as Mihon does. A Mihon source's memo on a series or chapter (data it keeps to open it again) is stored with it and set back on the series or chapter rebuilt in a later run. |
| `ExtensionImages` | same | Fetches covers and pages through the extension's own HTTP client. Each address handed to the image loader ends in a `#mihon=` tag naming the source by its full key (so sources of two kinds with the same numeric id stay apart), with a code only this install can compute; an interceptor on the app's media client answers those. Stored covers are plain addresses, tagged each time one is drawn or saved for a post (`SeriesCovers.address`), for both kinds. A tag needs only the series' source key, so this loads nothing; the source's extension alone loads when the image is first requested, and a cover whose source is not on loads as an ordinary request. |
| App seam | `app/.../extensions/ExtensionSourceHost.kt`, `app/.../di/ExtensionSources.kt` | The application creates the host. Nothing loads at startup: the extensions that are on load once, off the main thread, the first time the Series tab asks for sources or an image address ending in `#mihon=` is requested. The manifest holds `QUERY_ALL_PACKAGES`, as Mihon does: extension packages share only a declared feature, which a `<queries>` element cannot match. |
| Series | `feature-series`, `app/.../series/`, `core-data` `SeriesRepository` | The Series tab and series page, the Extensions page (also reached from Settings), the Viewer session over a series' chapters, and the `series` and `series_entries` tables that keep followed series, read state and progress. Pages are never stored. |
| Backup | `core-data` `SeriesBackupRecords.kt`, `TachiyomiBackup.kt`, `RoomProfileBackupStore` | Profile backups hold the series the user follows, started reading or chose a reading mode for, with every stored chapter's read state and last page (`library.series`, backup version 3; a backup without series is still written as version 2). Series are stored once for the whole app, not per profile, so they are backed up once. A restore adds: a series already stored becomes followed, takes a reading mode and a Continue point if it had none, and each chapter takes the further progress of the two sides; a series or chapter without a memo takes the backup's. A chapter only the backup has arrives as not listed when the device already holds the series' list, so it is not counted as unread. A series over 20,000 chapters is cut to that many, keeping the Continue point, then chapters with progress, then listed ones, so every backup the app writes is one it restores. A cover is written and read as the plain address the source gave, so a restored series shows its cover at once; the series' own source decides how it is fetched. Trust is never in a backup, so a restored series shows "Extension unavailable" until its extension is installed and turned on. Restore backup also takes a Mihon, TachiyomiJ2K or Anikku backup (`.tachibk`, gzip around protobuf; manga in field 1, Anikku's anime in field 501) and merges its series the same way (`ProfileBackupService.importSeries`): those apps name sources by the same numbers and series and entries by the same addresses, so each lands on its extension source here (`SeriesKind.extensionSourceKey`). A series comes along when followed or started, with read state, last page or playback position, the entry history read last as its Continue point, and the memos of the series and its chapters (fields 112 and 13). |

## Anime extensions

An Aniyomi extension declares the `tachiyomi.animeextension` feature and its interface version as the major part of its
version name. Versions 14 and 16 load; nearly every extension that declares 14 is built against 16's server list anyway.
Its sources' ids carry their own prefix (`custom_aniyomi_<id>`), so an anime and a manga source with the same number are
different sources everywhere the app keeps an id.

**Streams.** An episode's media is a list of `MediaStream`s (`AniyomiStreams`). Whether a source has the server list
is read once from the methods its own classes declare, as Aniyomi does, never by trying a request. The host asks for
the episode's servers, then for each server's videos at once, giving each server about 6 s; a source with only the
older single call is asked that instead, with about 50 s. Work runs in the host's detached scope, on threads of its
own, and is left there when its time runs out or the caller stops waiting, never joined, since extension code often
ignores cancellation: a server that hangs costs the episode nothing, and a Viewer that closes stops waiting at once.
When no server has a video, the lookup reports the most useful failure (a browser check first, and every server's
browser check is reported even when another server answers), else that it ran out of time; servers that answered with
nothing give no streams. Streams keep the order the source's own settings sort them in. Each is labelled with the
quality and server names the extension gives, typed as HLS or DASH by its address, or by its name when the address
says nothing, and carries its subtitles and separate audio as tracks; one without headers of its own gets the
source's, its browser agent included. Some extensions serve their streams from inside the app's process, so the app
allows cleartext to `127.0.0.1` and `localhost`.

**Seasons.** An anime grouped into seasons is refused with "Seasons aren't supported yet". Only its details tell, so
an anime not listed in this process, as the background new-episode check asks about, has them asked for first.

**QuickJS.** About a third of anime extensions (and some manga ones) evaluate scripts with QuickJS
(`app.cash.quickjs`), so the host has a JavaScript engine, as Mihon and Aniyomi do. Cash App's last release (0.9.2,
2021) has a native library aligned for 4 KB pages, which Android flags and devices with 16 KB pages may refuse to load,
so the host uses the fork Mihon ships (`com.github.zhanghai.quickjs-java`, from JitPack): the same classes and public
API, built with an NDK that aligns for 16 KB.

## Release builds

Release builds shrink and rename code with R8, which cannot see that an extension will call `okhttp3.OkHttpClient` or
`kotlin.collections.CollectionsKt` by name. `consumer-rules.pro` therefore keeps the public members of every package
the sampled extensions referenced: this module's Mihon and Aniyomi API, QuickJS whole (its native library looks its
classes up by name), and the public packages of OkHttp, Okio, Jsoup,
kotlinx.serialization, kotlinx.coroutines and the Kotlin standard library. Internal packages are left to R8 except two
members one extension each used.

Size. `:app:assembleRelease` (the APK the Release workflow ships, unsigned here) grows from 10,917,004 bytes on `main`
at `d2490c40` to 15,252,345 bytes, +4.34 MB (+40%), before anime extensions. QuickJS adds a 988 KB native library
for `arm64-v8a` (854 KB before the 16 KB-aligned fork); the release build was not measured again.

Why whole packages. Three rule sets were built and measured before the feature merged (at `f17caecf`), and each APK was
checked symbol by symbol against the 300 manga extensions (anime extensions came later):

| Rules | APK | Growth | Sampled extensions that link |
| --- | --- | --- | --- |
| None | 10,867,852 bytes | | |
| Exactly the 1,508 sampled classes and members | 13,153,339 bytes | +2.29 MB (+21%) | 294 of 300 |
| **The packages they are in (chosen)** | 15,170,425 bytes | +4.30 MB (+40%) | 294 of 300 |
| Every class of those libraries | 17,204,943 bytes | +6.34 MB (+58%) | 294 of 300 |

The six that do not link needed what the host lacked then (protobuf, a JavaScript engine, an image decoder); it has
had a JavaScript engine since anime extensions came. The smallest set was not chosen because the sample is 300 of
1,711 extensions: rules made from 250 of them left out something that about 30% of the other 50 call, rules that keep
each sampled class whole about 7%, and rules by package 0 to 8% (five random splits each). About 1.5 MB of the growth is the zstd decoder, a native library that every
extension's HTTP client names. QuickJS is the second native library. The app packages both for `arm64-v8a` and
`x86_64` only, and its phone builds for `arm64-v8a` alone.

`extension-symbols.txt` lists what the samples of 300 manga and 168 anime extensions referenced, in the form R8
reports what it kept. The release part of the checks (`scripts/check.sh --part release`) fails when a release build no
longer keeps one of them. It lists host names only; the survey's scripts and the extension indexes are not in the
repository.

Proved on an emulator with the minified `releaseAcceptance` build: the tab appears once an extension is installed;
a manga extension turns on, browses, opens a series and a chapter whose pages download, and keeps a changed setting across a
restart; following a series and forcing the update job (`cmd jobscheduler run -f <package> 3101`) ends with the job
finished; a second extension browses and lists chapters. Logcat shows no missing class or member.

## Try it

Use an emulator. The extension's code runs inside the app with the app's permissions, so turn on only extensions you
trust, and follow `AGENTS.md` ("Personal-device Safety") before installing anything.

Without the app, on any extension APK:

```sh
./gradlew :extension-host:assembleDebugAndroidTest
APK=extension-host/build/outputs/apk/androidTest/debug/extension-host-debug-androidTest.apk
python3 scripts/verify_device_apk.py $APK --package com.theoriacodex.extensionhost.test
adb -s <emulator> install -r -t $APK
adb -s <emulator> shell mkdir -p /sdcard/Android/data/com.theoriacodex.extensionhost.test/files
adb -s <emulator> push extension.apk /sdcard/Android/data/com.theoriacodex.extensionhost.test/files/
adb -s <emulator> shell am instrument -w -r -e extensionApk extension.apk \
  -e class com.theoriacodex.extensionhost.ExtensionHarnessTest \
  com.theoriacodex.extensionhost.test/androidx.test.runner.AndroidJUnitRunner
```

It lists popular series, opens one's first chapter, fetches a cover and a page, and prints counts, timings and request counts as
`harness=` lines. It never prints or saves images.

An anime extension runs the same way with `-e animeApk extension.apk -e class
com.theoriacodex.extensionhost.AnimeHarnessTest#animeExtensionStreamsEndToEnd`: it lists popular series, opens one,
lists its episodes, resolves the earliest episode's streams, names what the first stream's opening bytes are, and says
whether the extension started QuickJS. `AnimeHarnessTest#quickJsEvaluates` needs no extension.

In the app, install the extension the way Mihon does, as a package (for tests, a debug build also finds a file pushed
to `/sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/`):

```sh
adb -s <emulator> install -r extension.apk
```

Open the Series tab, go to Extensions and turn the extension on. The tab then browses its popular or latest series, searches
one source with its own filters or every source that is on at once, and opens a series' page, from which a chapter opens in the Viewer. Two live tests
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
gets one local notification that opens its page, whichever check found them. The same test class proves it, with the
job run by hand in between (or `run ExtensionSourceLiveTest#pullingDownOnFollowingNotifiesTheNewChapter` in its place):

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

**The host surface is small.** Across a random sample of 300 manga extensions:

- None references RxJava. The whole sample uses the coroutine API, so the host needs no Rx.
- They use about 29 host classes plus 4 service-locator classes. The locator is asked for only four types: `Json`
  (257 extensions), `Application` (207), `ProtoBuf` (3) and `NetworkHelper` (1).
- None ships native code. The median APK is 78 KB.
- Checked symbol by symbol against the built debug app: 205 (68%) link completely, 89 (30%) lack only
  `androidx.preference` (their settings screen, which the host now provides), and 6 (2%) needed something the host
  lacked (protobuf, a JavaScript engine, an image decoder).
- Mihon ships the same OkHttp (5.5.1) and kotlinx.serialization (1.13.0) as Theoria.
- Mihon's `source-api` changed in 14 commits in the last year, three of them the move to API 1.6.

**Two requirements were not visible in the API.** Both were found by running the first manga extension tried:

- Every extension carries a shared library that rebuilds the host's HTTP client and refuses to run unless it finds
  interceptors named `UncaughtExceptionInterceptor`, `UserAgentInterceptor` and `CloudflareInterceptor` in it
  (300 of 300 sampled). The host must keep classes with those names.
- Most extensions decode JSON from the response stream with `kotlinx-serialization-json-okio` (246 of 300), which
  Theoria did not ship.

**Images must go through the extension.** One site answers a cover request from any other HTTP client with a bot
check, and builds each page's address per request from a short-lived image server. Mihon fetches both through the
source's client; so does this host.

**Timings on an emulator (that first extension).** Load the APK: 45 ms. Popular listing: 1 request, about 1 s. Open a series
(details, chapter list, page list): 4 requests, about 1.5 s alone. In the app, opening a series right after a listing
took 4 to 12 s: the covers go through the same rate-limited client, and the Viewer also resolves the next post.

**Ten more manga extensions through the harness.** Seven listed, opened a series and fetched a page image. One more
opened a series after a host fix (its details page returns no title, which Mihon tolerates) but could not fetch a
page: the site's own image link was dead. One sat behind a browser challenge, which the host then could not pass (see "Browser checks"). One gets HTTP 403 for page
images through its own client while a plain request succeeds; whether Mihon gets the same was not checked.

**How the sample was measured.** The 300 were drawn at random from the index (seed 20261122). Each APK's references
to classes, methods and fields outside itself were resolved against the debug app's own code and the Android
framework. The scripts are not in the repository: they depend on the index, which is not linked here.

## What is missing

What the feature still lacks.

- **Unloading.** Loaded code cannot be unloaded. Turning an extension off stops its sources and images at once; its
  code stays idle in memory until the app's process ends.
- **Extensions outside the sample.** The keep rules cover the packages 300 manga and 168 anime extensions used. One that calls a class in
  another package of those libraries fails with a missing class or method in release builds only.
- **Failures in native code.** A source that throws is left out and says why. One that fails inside a library's native
  code (the zstd decoder and QuickJS are the only such libraries) ends the app's process instead, and the Series tab loads its
  first source when it opens.
- **A hand-modified backup.** A restored series', chapters' and cover's addresses and memos go to their extension as stored, and an
  extension builds its requests from addresses as it likes and may talk to several hosts, so the app has no origin to
  check them against. A backup edited by hand can thus make an approved extension request another host; the boundary
  is trust in the extension and in the file being restored.
- **One browser identity.** Extensions, Custom Sites and the in-app browser share the WebView's one cookie store, so
  an extension and a Custom Site on the same host share that host's cookies.
- **Browser checks in the background and in the Viewer.** The new-chapter job cannot show a check, so a followed series
  behind one is skipped that round; a chapter page that fails a check inside the Viewer shows the Viewer's usual
  failure.
- **Protobuf and extra image decoders**, used by about 1% of manga extensions.
- **Update checks in the background.** Repos are asked for updates only when the Extensions page opens, and nothing
  notifies about them.
- **Other extension API versions.** Only Mihon's API 1.6 and Aniyomi's versions 14 and 16 load, which is all the
  indexes held besides Aniyomi's newest, 17. Torrent streams and seasons are not supported.
- **A limit on calls.** Loading an extension, reading its settings and looking up an episode's streams have a time
  limit; a listing or page request that hangs inside extension code is bounded only by the HTTP client's timeouts.
- **Cancellation.** An image fetch that the app cancels still runs to completion on the extension's client.
- **Pages that share an image address.** A source whose pages name the same image address and differ only in the
  request's headers or body would show one image for all.

## Removing it

Delete `extension-host/`, `app/src/main/java/com/theoriacodex/app/extensions/` and its use in `TheoriaApplication`, and
`ExtensionSourceLiveTest`. Then remove the module's lines from
`settings.gradle.kts`, `app/build.gradle.kts` (the `abiFilters` line too), the root `build.gradle.kts`, `scripts/check.sh`,
`scripts/verify_device_apk.py`, `scripts/detekt_changed.sh`, `config/quality/hotspot-budgets.json` and its Detekt
baseline, the four library aliases and the `quickjs` version in `gradle/libs.versions.toml` (and the JitPack
repository it alone resolves from in `settings.gradle.kts`), the cleartext rule for
`127.0.0.1` and `localhost` in `app/src/main/res/xml/network_security_config.xml`, and the permissions, service and activity it added to the manifest. The seam
in `:app` and `:media-kit` (`SeriesDependencies`, `AppHttpClients`' `extensionMedia`) does nothing without a host, but the Series tab then has nothing to show, so it goes too (`TopLevelDestination.Series`); `feature-series` and the
series tables depend only on `SeriesSource`, not on this module.
