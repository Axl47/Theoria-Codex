# Theoria Macrobenchmarks

The module measures the isolated, R8-minified `com.theoriacodex.benchmark` app on physical
hardware. `benchmarkRelease` explicitly inherits release configuration; packaged verification requires
its AGP-produced R8 mapping and JSON wire contracts. Historical reports from the formerly unminified
benchmark target are incompatible and cannot serve as calibration baselines. Before a connected run, verify the task graph, packaged target and runner identities,
signing lane, manifest and absence of a package-mutating listener. The production package and
its data must never be installed, cleared or launched by this lane.

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest
```

Build-only verification does not produce performance evidence:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :app:assembleBenchmarkRelease :macrobenchmark:assembleBenchmarkRelease
```

## What the journeys exercise

Ten cold and ten warm startup iterations use
`CompilationMode.Partial(BaselineProfileMode.Require)`. Interaction journeys run five iterations
in `:benchmarkFixture`, recording its frame timing and peak RSS/heap/GPU memory. Regenerate
profiles after structural changes settle and before the final physical measurements; a required
profile being present does not prove that it covers the new code.

The five original methods remain component/control workloads (with fresh minified baselines required): startup, the 24-video shared-card
scroll, its legacy bundled-retriever duration workload, and six-video Viewer swipes. Their shared
card fixture is deliberately smaller than the real app. The original MP4 bytes remain frozen.
Do not describe that retriever workload as the production remote-duration path.

Workload version 2 adds two complete app journeys:

- `mixedMediaFiveFeedJourney` launches the real app shell and production navigation, ViewModels,
  coordinators, filtering, paging, Room and settings stores. It navigates Search, For You,
  Watched Recents, Codex detail and Creator Profile through their actual controls. Each feed
  scrolls down and back over MP4, GIF, animated WebP and Ugoira fixtures. Every visibly presented
  media preview must show changing image pixels; merely reporting `isPlaying` or displaying a
  spinner cannot satisfy this assertion. Two separate frame changes per preview are required.
  The version-2 video is a frozen 640×360 H.264 test pattern with changing luminance across the
  whole frame, so thin clipped image strips also carry observable motion. The journey checks that Settings releases all preview
  leases, returns to Search, and backgrounds/resumes the same activity and route owners.
  An ordered broadcast to the existing benchmark-only receiver verifies actual pool leases are
  positive in foreground Search and zero on Home; the retained session and moving frames must
  return on resume. The receiver has no production component or additional permission.
- `durationAcquisitionFiveFeedJourney` starts the same app graph and posts with unknown durations.
  After visible autoplay is established, an explicit start signal enables the real unknown-duration
  setting. Production route demand and scroll/lifecycle events reach `MediaDurationCoordinator`,
  `MediaDurationAcquisitionEngine` and `BoundedMediaDurationProbe`. The external byte transport
  serves the frozen MP4 with valid bounded ranges and a deterministic 250-ms response delay.
  All 24 posts must settle to Known or Unsupported; at least one bounded byte acquisition must
  complete. Already-visible authoritative players may publish Known metadata before the signal,
  just as in the normal app. The JVM `DurationRouteAcquisitionTest` also proves that a real range
  operation is canceled by scrolling, stays paused, and resumes without copying the Post list.

The full app fixture uses `app/src/fixtures` with a fresh activity-owned storage directory inside
its isolated app sandbox. Providers, media and credentials are controlled local fixtures; there
are no live provider calls, translation calls or downloads. The target manifest removes Internet,
install-package permission, production deep links, App Links verification and FileProvider.
The normal production container is skipped only in the exact fixture process. Source and
packaged-artifact isolation checks remain separate from behavior tests.

## Metric meanings and comparison

Frame sample keys retain AndroidX's suffix order, such as `frameDurationCpuMsFixture` and
`frameOverrunMsFixture`. Compare the same percentile across compatible device/build/harness runs.
Memory metrics describe the fixture process, not the whole device.

| Metric label | Meaning |
| --- | --- |
| `previewPrepareCount` | Legacy shared-card lease/acquisition trace count; not necessarily new ExoPlayer objects. |
| `previewFirstFrameCount` | First rendered frame per attached preview listener. |
| `viewerPrepareCount` | Viewer player preparations. |
| `viewerFirstFrameCount` | First rendered Viewer frame per player/listener. |
| `mediaLoadCount` | Media3 load starts, including retries and later loads. |
| `previewPlayerCreateCount` | Actual feed ExoPlayer object creation. |
| `previewPlayerPrepareCount` | Preparations that reach the paced queue. |
| `previewPlayerRebindCount` | Existing players rebound after clearing the previous media. |
| `previewPlayerCoolCount` | Idle media bindings stopped and cleared. |
| `previewPlayerReleaseCount` | Actual paced ExoPlayer releases. |
| `durationDemandCount` | Submitted per-key duration demands, not one request per iteration. |
| `durationResolveCount` | Provider-resolution or legacy fixture resolver attempts. |
| `durationProbeCount` | Probe attempts. The legacy fixture uses the bundled retriever. |
| `durationPublishCount` | Coordinator metadata-map publications, not rewritten Post lists. |
| `durationSettledCount` | Coordinator transitions to no outstanding work. |
| `durationWorkloadSumMs` | Sum of per-key acquisition spans; overlapping work is counted separately. |
| `durationBatchSumMs` | Legacy component signal-to-final-decision interval. |
| `fixtureByteRangeCount` | Integrated workload bounded byte requests, including canceled attempts. |
| `journeyDurationBatchSumMs` | Integrated setting-enable signal until all 24 decisions are terminal. |

`TheoriaFixtureResponseBytes` in Perfetto is a cumulative counter of returned fixture byte
payloads. The journey checks its bounded volume, while the JSON comparator gates range-request
counts. There is currently no ordinary JSON metric gate for byte volume or real Internet throughput.
Player active/total counters in the traces distinguish concurrent visible leases from object churn.

The comparison tooling must reject incompatible device, compilation, harness and metric versions.
Calibrate tolerances from at least three independent fresh baseline reports; do not promote one
old component result into a baseline for the full-app version-2 workload. Source assertions or a
successful APK build do not replace measured results. New journeys require fresh physical evidence.

Gradle copies the complete result directory to:

```text
macrobenchmark/build/outputs/connected_android_test_additional_output/
  benchmarkRelease/connected/<device>/
```

Keep its AndroidX JSON and every iteration's Perfetto trace when calibrating or comparing runs.
Emulator results are diagnostic only.

Do not configure `androidx.benchmark.junit4.SideEffectRunListener`. In AndroidX Benchmark
1.5.0-alpha07 it disables 41 unrelated packages and later unconditionally enables them. Its
transitive bytecode may remain packaged; only configuring/instantiating the listener causes these
side effects. Packaged runner verification checks the actual instrumentation configuration.

The version-2 MP4 was generated once with the following command and is checked in. Do not regenerate
it between baseline and candidate measurements; the comparison artifact hashes own its identity.

```bash
ffmpeg -f lavfi -i 'testsrc2=size=640x360:rate=24:duration=2' \
  -vf 'eq=brightness=0.15*sin(8*PI*t):eval=frame' \
  -c:v libx264 -preset medium -crf 23 -an -movflags +faststart benchmark_motion.mp4
```
