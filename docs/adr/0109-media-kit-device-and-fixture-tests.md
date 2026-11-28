# 0109. Stream tests run against a captured loopback fixture, and device and coroutine test helpers work around known platform limits

Status: Accepted

## Context

Playback tests need real Media3 players, real decoders and reproducible streams without network access, in a test APK whose process has no application infrastructure.

## Decision

- `StreamFixtureServer` is a loopback HTTP/1.1 server used on the JVM and on a device. It answers 403 to requests without `STREAM_FIXTURE_HEADERS`, 404 to unknown paths, honours single byte ranges and records every request. The episode in `stream-fixture/` is an HLS master whose video-only variant has AES-128 segments and relative addresses, beside a separate audio playlist (also without extension), a WebVTT file and an ASS file at a `.vtt` address. The binary files were produced with ffmpeg (a 160x90 `testsrc`, 4 s, baseline H.264, keyframe every 15 frames, 2 s HLS segments encrypted with a 16-byte key, and a 440 Hz AAC sine at 24 kbps); `master.m3u8` and the subtitles are hand-written. The device-only `looping-clip.mp4` is a 3 s, 320x180, 24 fps `testsrc2` in baseline H.264 at 150 kbps with a keyframe every second, plus a 440 Hz mono AAC sine at 32 kbps, faststart, made with ffmpeg; it needs its audio track, because the looping-seek freeze it guards against (ADR 0119) runs through the audio clock. The device-only `single-keyframe-clip.mp4` is a 5 s, 320x180, 24 fps `testsrc2` in H.264 with one keyframe at 0 and no B-frames, plus the same sine at 32 kbps, faststart; like a short booru video, it has nothing to snap to but its first frame (ADR 0119).
- `StreamPlaybackDeviceTest` runs in `:media-kit`'s own test APK (`scripts/verify_debug_device.sh media`) whose process has no application infrastructure, so the one the tests share is the only one. `VideoPlaybackInfrastructure` opens its `SimpleCache` directories when constructed and only one instance per process may own them, so the class keeps one in its companion object, and each test removes any listener it added and returns its player to that pool instead of releasing a player the pool still owns. Streams skip the shared cache so one test's bytes cannot serve another. It skips when the failing decoder is the emulator's own (`c2.goldfish.*`): the Android 17 emulator image denies that decoder the app's input buffers (SELinux, `memfd_file`), so it decodes no video; the Android 16 emulator still runs the test in full. `EpisodeClipExportDeviceTest` skips the same way, reading the codec from the `ExportException` its clip failure carries. `:app`'s `ViewerVideoResumeJourneyTest` (ADR 0181) skips the same way when it fails: it has no handle on the Viewer's player, so it reads the codec from the `PlaybackException` the Viewer reports to `Diagnostics` under `playback`, recording through a sink that forwards to the one it replaced and puts it back afterwards, which is why `Diagnostics.install` returns the previous sink. All three share `assumeTheEmulatorDecoderWorks`, which lives in `:media-kit`'s test fixtures so both modules' device tests reach it. App journeys whose videos only need to load, not decode (`ViewerStillWidthJourneyTest`, `ViewerVideoPrefetchDeviceTest`), pass on Android 17 and stay unguarded.
- Each module with device tests has its own test APK and owner in `scripts/verify_debug_device.sh` (`app`, `data`, `media`, `viewer`), and CI's connected lane builds, runs and keeps the reports of every one. `:feature-viewer`'s scrub preview test (ADR 0176) uses a copy of the single-keyframe clip.
- `OfflineQueueRecoveryTest` reads the queue with `runBlocking` on the test thread because suspending on a real file read would leave `runTest` with nothing to run and it would then skip virtual time to the timeout of a post still being acquired.
- `OffMainComputationTest` waits for the off-main worker to park before `waitForIdle`, since a parked worker has finished resuming the coroutine, which either ran or was queued for the composition's dispatcher.

## Consequences

Playback tests are deterministic and offline. Regenerating a fixture binary changes every assertion that names its bytes.

## Applies to

- `media-kit/src/sharedTest/kotlin/com/theoriacodex/app/viewer/StreamFixtureServer.kt`: `StreamFixtureServer`, `streamFixtureRoutes`, `STREAM_FIXTURE_HEADERS`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/StreamPlaybackDeviceTest.kt`: `StreamPlaybackDeviceTest`, `skipIfTheEmulatorDecoderFailed`
- `media-kit/src/testFixtures/kotlin/com/theoriacodex/app/viewer/EmulatorDecoder.kt`: `assumeTheEmulatorDecoderWorks`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/ViewerVideoResumeJourneyTest.kt`: `recordPlaybackErrors`, `skippingTheEmulatorDecoder`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/diagnostics/Diagnostics.kt`: `Diagnostics.install`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/EpisodeClipExportDeviceTest.kt`: `skippingTheEmulatorDecoder`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineQueueRecoveryTest.kt`: `loadNow`
- `media-kit/src/test/kotlin/com/theoriacodex/app/ui/components/OffMainComputationTest.kt`: `landOffMainCompute`

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/VideoStreamPlaybackTest.kt`: `the fixture server refuses a request without the stream headers, answers 404 to an unknown path and serves a byte range (ADR 0109)`
- `scripts/tests/test_device_validation_workflow.py`: `test_connected_lane_executes_and_retains_every_instrumentation_owner_adr_0109`
- Left to prose: that the device tests skip only on the emulator's own decoder failing, and that Android 16 runs them in full, shows only on an emulator; `loadNow` and the park-then-idle order in `landOffMainCompute` are how two tests avoid scheduler traps, and those tests are their own guard; how the fixture binaries were made is a record of their origin.
