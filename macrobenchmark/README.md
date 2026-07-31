# Theoria Macrobenchmarks

This module measures the release-like `benchmarkRelease` target independently from Baseline
Profile generation. Run it only on physical hardware:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest
```

Build-only verification is deterministic and does not produce performance evidence:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :app:assembleBenchmarkRelease :macrobenchmark:assembleBenchmarkRelease
```

The suite records ten cold and ten warm startup iterations with
`CompilationMode.Partial(BaselineProfileMode.Require)`. Search and Viewer run five iterations
against a benchmark-only secondary process, with frame timing and peak RSS/heap/GPU memory.
The fixture is an offline APK resource and never opens normal repositories or providers.
The target uses the `.benchmark` application ID suffix, so neither startup nor fixture runs can
overwrite the installed production app or enter its storage sandbox.
Its packaged manifest retains only the launcher and explicit fixture surfaces: production deep
links, App Links verification, install permission, network permission, and FileProvider are removed.

Do not configure `androidx.benchmark.junit4.SideEffectRunListener` for this suite. AndroidX
Benchmark 1.5.0-alpha07's listener disables 41 unrelated packages at setup and unconditionally
enables them at teardown, so it cannot preserve a personal device's prior app state. The suite
accepts ordinary background-device variance instead of mutating unrelated packages.

Trace count meanings:

- `previewPrepareCount`: Search preview ExoPlayer creations that reach `prepare()`.
- `previewFirstFrameCount`: Search preview players that render their first frame; loop callbacks
  after that first transition are excluded.
- `viewerPrepareCount`: Viewer ExoPlayer creations that reach `prepare()`.
- `viewerFirstFrameCount`: Viewer players that render their first frame; loop callbacks after that
  first transition are excluded.
- `mediaLoadCount`: Media3 load-start callbacks, including retries or subsequent loads.

Gradle copies the benchmark JSON and one Perfetto trace per measured iteration under:

```text
macrobenchmark/build/outputs/connected_android_test_additional_output/
  benchmarkRelease/connected/<device>/
```

Retain that complete device directory when comparing F10 with later performance changes. The
JSON owns device/build metadata and percentile summaries; the trace files own per-iteration
diagnostics. Emulator output is diagnostic only and is not accepted as numeric evidence.
