# 0185. Device, live and benchmark tests use isolated packages, text-only reports and fixture providers

Status: Accepted

## Context

The app's device tests drive the real app graph, and some of them reach live provider sites, real accounts or the user's installed library. Provider feeds can show content that must not be captured, and the production package and its data must not be touched by automated lanes. Some Android behaviours also differ from the JVM or from Compose's own coordinate system, so a few tests have to work around them.

## Decision

- Journey tests (`AppJourneyFixture`, `JourneyAppContainer`) run the real screens, owners and navigation. Only external provider input and storage location are fixtures; sources named in `providerShapedSources` go through the real Danbooru adapter, fed generated JSON by `ProviderShapedSource`, so no provider data is recorded or stored. `JourneyNoNetworkHttpClient` fails every unconfigured request locally, before a socket opens, and `JourneyAppContainer` waits for listing picture hashes (`ListingPictures` budget of 10 s) so journeys check what hashes do rather than how fast the device is. `coldGraphRelaunch` starts a new Activity, view models, repositories and Room connection; only on-disk fixture data survives. Solid-color fixture images are too flat to hash, so `patternImage` exists for tests that need a picture hash.
- The `fixtures` source set is compiled into the debug, `benchmarkRelease` and `nonMinifiedRelease` variants only, never into release builds. `BenchmarkFixtureActivity` and `BenchmarkAppJourney` exist only in `benchmarkRelease` (`com.theoriacodex.benchmark`) and `nonMinifiedRelease`, the baseline-profile build (`com.theoriacodex.baselineprofile`), which also compiles the fixtures and launches `BenchmarkFixtureActivity`; they never initialize production repositories or providers, and use a fresh directory per Activity inside the benchmark app's sandbox. `BenchmarkDurationStartReceiver` is a benchmark-only signal that production never registers. `OfflineImageDiagnostics` is test-only and never enabled in the shipped app.
- Live tests (`SeriesLiveDriver.requireOptIn`, `LiveDataUsageSessionTest`, `LiveLatencyBenchmarkTest`, `LibrarySnapshotExportTest`, `HitomiLiveTransportDeviceTest`) skip unless an instrumentation argument opts in, so the default connected-device lane never reaches live sites or real accounts. The opt-in only enables live traffic: production stays outside the test lanes. `SeriesLiveDriver` takes a `targetPackage` argument, and the anime tests use it to drive the minified `com.theoriacodex.acceptance` build instead of the Debug app. They drive the app through accessibility nodes, report titles, labels, counts, times and byte counts only, and never capture the screen or log provider media. The one screenshot helper, `AppJourneyFixture.saveScreenshot`, is for fixture media only: it captures the whole display with no check on what is shown. Live latency runs measure one case per instrumentation run because connection pools live and die with the process.
- `MediaBufferingBenchmarkTest` runs only with `-e mediaBench true`. It serves generated media from `BenchMediaServer`, a throttled loopback server in the app process, so loopback socket buffers bound how far a response runs ahead of the reader, as a phone's TCP window does. Media in it is never provider media, and its post uses a non-Gelbooru source id because a Gelbooru post's media would be repaired to https while the server is plain HTTP.
- Tests that create state in a shared package guard against production: `ViewerPageDecodeDeviceTest.stripServer` refuses `com.theoriacodex` itself, and the download tests (`PostDownloadDestinationDeviceTest`, `PostDownloadProcessRecoveryDeviceTest`) remove only their own DownloadManager rows and private external directory, never the user's Downloads. The recovery test pins a real native enqueue until the host force-stops its process, then reads the result in a second launch, with no fake DownloadManager.
- `RegexCompatibilityDeviceTest` runs on a device because Android compiles regexes with ICU, which rejects patterns the JVM accepts (an unescaped `}` or `]`). It initializes every app class from the installed APK, listed through `DexFile`, the only way to list them, so the deprecation is suppressed.
- Source-parsing tests (`SourceCallThreadJourneyTest`, `SourceParsingThreadJourneyTest`) are the device proof that provider parsing stays off the main thread. `SourceParseRecorder` forwards every call to the app's checker and restores it after each test, because journey classes share a process.
- `AnimeSeriesLiveTest` has two methods that share one story across a restart: `anEpisodePlaysSwitchesStreamAndIsLeftPartway`, then `theEpisodeResumesAfterARestartAndPlaysOnIntoTheNext`. Each runs as its own `am instrument` invocation, with the `extensionName` and `seriesQuery` arguments, and the target package is stopped with `am force-stop` in between, since restarting the Debug test process does not stop a separately targeted acceptance app. Running both in one invocation cannot establish a restart.
- `ExtensionSourceLiveTest.aFollowedSeriesForgetsItsNewestChapter` deletes the chapter row directly, because the app itself never forgets a chapter: one its source drops is kept and hidden, and is not new when it returns. `unreadSeries` counts a series from storage because the app may be refreshing the page an earlier run left open and the source refuses two requests for one series at a time.
- Touch and window workarounds: swipes start and end away from the system back-gesture edges (the journeys use 15% to 85% or 20% to 80% of the width), because an edge swipe is the system's Back; the Search composer is its own Dialog window, so system Back rather than the Activity's dispatcher closes it; `OfflineImageDiagnostics` adds the window's offset because UiAutomation captures the display while Compose reports window-relative bounds; its `close` restores the original image loader and must not shut down the caches that a `newBuilder` loader shares with the application.

## Consequences

Device lanes cannot reach production data or live providers unless the person running them opts in, and reports are safe to keep. Fixture-based journeys are deterministic but exercise adapters only where `providerShapedSources` says so. Opening a new source to journeys means adding generated provider JSON for it.

## Applies to

- `app/src/androidTest/java/com/theoriacodex/app/journeys/AppJourneyFixture.kt`: `AppJourneyFixture`, `providerShapedSources`, `patternImage`, `coldGraphRelaunch`, `saveScreenshot`
- `app/src/fixtures/java/com/theoriacodex/app/fixtures/JourneyAppContainer.kt`: `JourneyAppContainer`
- `app/src/fixtures/java/com/theoriacodex/app/fixtures/JourneyExternalServices.kt`: `JourneyNoNetworkHttpClient`
- `app/src/fixtures/java/com/theoriacodex/app/fixtures/ProviderShapedSource.kt`: `ProviderShapedSource`, `providerShapedPosts`
- `app/src/benchmarkRelease/java/com/theoriacodex/app/benchmark/BenchmarkFixtureActivity.kt`: `BenchmarkFixtureActivity`
- `app/src/benchmarkRelease/java/com/theoriacodex/app/benchmark/BenchmarkAppJourney.kt`: `BenchmarkAppJourney`
- `app/src/benchmarkRelease/java/com/theoriacodex/app/benchmark/BenchmarkDurationWorkload.kt`: `BenchmarkDurationStartReceiver`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/OfflineImageDiagnostics.kt`: `OfflineImageDiagnostics`
- `app/src/androidTest/java/com/theoriacodex/app/live/SeriesLiveDriver.kt`: `SeriesLiveDriver`, `requireOptIn`
- `app/src/androidTest/java/com/theoriacodex/app/live/AnimeSeriesLiveTest.kt`: `AnimeSeriesLiveTest`
- `app/src/androidTest/java/com/theoriacodex/app/live/LiveDataUsageSessionTest.kt`: `LiveDataUsageSessionTest`
- `app/src/androidTest/java/com/theoriacodex/app/live/LiveLatencyBenchmarkTest.kt`: `LiveLatencyBenchmarkTest`
- `app/src/androidTest/java/com/theoriacodex/app/live/ExtensionSourceLiveTest.kt`: `aFollowedSeriesForgetsItsNewestChapter`, `unreadSeries`
- `app/src/androidTest/java/com/theoriacodex/app/viewer/BenchMediaServer.kt`: `BenchMediaServer`
- `app/src/androidTest/java/com/theoriacodex/app/viewer/MediaBufferingBenchmarkTest.kt`: `MediaBufferingBenchmarkTest`
- `app/src/androidTest/java/com/theoriacodex/app/viewer/ViewerPageDecodeDeviceTest.kt`: `stripServer`
- `app/src/androidTest/java/com/theoriacodex/app/media/PostDownloadProcessRecoveryDeviceTest.kt`: `PostDownloadProcessRecoveryDeviceTest`
- `app/src/androidTest/java/com/theoriacodex/app/source/RegexCompatibilityDeviceTest.kt`: `RegexCompatibilityDeviceTest`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/SourceParseRecorder.kt`: `SourceParseRecorder`
