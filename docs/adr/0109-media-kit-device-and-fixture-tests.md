# 0109. Stream tests run against a captured loopback fixture, and device and coroutine test helpers work around known platform limits

Status: Accepted

## Context

Playback tests need real Media3 players, real decoders and reproducible streams without network access, in a test APK whose process has no application infrastructure.

## Decision

- `StreamFixtureServer` is a loopback HTTP/1.1 server used on the JVM and on a device. It answers 403 to requests without `STREAM_FIXTURE_HEADERS`, 404 to unknown paths, honours single byte ranges and records every request. The episode in `stream-fixture/` is an HLS master whose video-only variant has AES-128 segments and relative addresses, beside a separate audio playlist (also without extension), a WebVTT file and an ASS file at a `.vtt` address. The binary files were produced with ffmpeg (a 160x90 `testsrc`, 4 s, baseline H.264, keyframe every 15 frames, 2 s HLS segments encrypted with a 16-byte key, and a 440 Hz AAC sine at 24 kbps); `master.m3u8` and the subtitles are hand-written.
- `StreamPlaybackDeviceTest` runs in `:media-kit`'s own test APK (`scripts/verify_debug_device.sh media`) whose process has no application infrastructure, so the one the test makes is the only one; the stream skips the shared cache because only one instance per process may open its directory. It skips when the failing decoder is the emulator's own (`c2.goldfish.*`): the Android 17 emulator image denies that decoder the app's input buffers (SELinux, `memfd_file`), so it decodes no video; the Android 16 emulator still runs the test in full.
- `OfflineQueueRecoveryTest` reads the queue with `runBlocking` on the test thread because suspending on a real file read would leave `runTest` with nothing to run and it would then skip virtual time to the timeout of a post still being acquired.
- `OffMainComputationTest` waits for the off-main worker to park before `waitForIdle`, since a parked worker has finished resuming the coroutine, which either ran or was queued for the composition's dispatcher.

## Consequences

Playback tests are deterministic and offline. Regenerating a fixture binary changes every assertion that names its bytes.

## Applies to

- `media-kit/src/sharedTest/kotlin/com/theoriacodex/app/viewer/StreamFixtureServer.kt`: `StreamFixtureServer`, `streamFixtureRoutes`, `STREAM_FIXTURE_HEADERS`
- `media-kit/src/androidTest/kotlin/com/theoriacodex/app/viewer/StreamPlaybackDeviceTest.kt`: `StreamPlaybackDeviceTest`, `skipIfTheEmulatorDecoderFailed`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/OfflineQueueRecoveryTest.kt`: `loadNow`
- `media-kit/src/test/kotlin/com/theoriacodex/app/ui/components/OffMainComputationTest.kt`: `landOffMainCompute`
