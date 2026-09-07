# Media / performance remediation

- [~] F09 Run actual OCR coordinator with real bitmap decode, injected recognizer, crop mapping/grouping, fallback bounds and cleanup.
- [x] F09 Exercise actual translation HTTP transport with bounded/oversized bodies and cancellation.
- [x] F06 Assert independent translation phrase/character boundaries and exact batch payload preservation.
- [x] F03 Add production route duration workload with scrolling/lifecycle demand and bounded fixture transport.
- [x] F03 Add mixed-animation journeys across the five real feeds and tab/background/fling transitions.
- [x] F07 Replace media behavior source assertions and duplicate policy cases with behavior evidence.
- [x] Update benchmark metric definitions/version information; physical evidence stays pending until root runs compatible hardware measurements.
- [ ] Send final source/test inventory to root for combined validation. No independent Gradle/device/network commands.

Ownership: app media/viewer, relevant app-logic tests, app benchmarkRelease, macrobenchmark source/README. Root owns Gradle/scripts/CI. App-flow owner owns normal feed screens and fixture/container integration seam.

## Implemented evidence (pending root validation)
- `ViewerOcrCoordinatorTest`: real Coil PNG decode, bounded software input, injected ML Kit recognizer, grouping, mapped/deduplicated crops, conditional contrast fallback and recycling after cancellation.
- `ViewerOcrCorpusDeviceTest`: real ready Japanese horizontal/vertical, Chinese, Korean models and Latin-only rejection. Missing models produce explicit assumptions/skips; no model download or translation.
- `TranslationHttpTransportTest`: real loopback POST, exact 64-KiB UTF-8 response, oversized success/error streams, no redirects, stalled-response cancellation. Production transport uses the shared cancellable HTTP connection boundary; caller cancellation does not wait for a JDK response-read lock, while bounded socket cleanup runs off the UI thread.
- `GoogleViewerTextTranslatorTest`: exact 32/33 phrase and 5,000/6,000 character boundaries, complete ordered batch payloads and results.
- `DurationRouteAcquisitionTest`: actual route VM -> coordinator -> acquisition engine -> bounded MP4 parser with real bundled bytes; scroll cancellation and no redundant cached work.
- Version-2 macrobenchmarks navigate all five real feeds with four animation kinds and screenshot motion assertions, Settings lease return and retained-task background resume. Their duration setting reaches the normal route/environment/acquisition owners through the shared fixture app graph.
- Removed media behavior source-spelling suites and duplicate/constant-only policy cases. Kept development identity, manifest and runner isolation guards. `MediaRequestFactoryTest` checks actual header/cache-mode objects; existing animated decoder device behavior remains.
- Root owns compilation/tests/device identity proof and physical runs. No performance result has yet been claimed.

## Validation feedback
- Root compiled all app, benchmark and Android-test sources successfully.
- Initial host run caught a real cancellation defect: synchronous HttpURLConnection disconnect could wait on the response-read lock, exceeding the 2-second cancellation assertion against a 30-second read timeout.
- Repaired by extracting the existing source HTTP client's detached interruptible IO/asynchronous disconnect lifecycle into `core-sources/.../CancellableHttpConnection.kt`, shared by both provider and translation transport. Root owns the focused rerun plus the aggregate batch.
- The bounded OCR rerun exposed the actual host fixture prerequisite: plain Application bypassed ML Kit's initialization provider, so InputImage creation failed before reaching the injected recognizer. Tests now initialize the SDK context through its idempotent `MlKitContext.initializeIfNeeded(context)` entry point while retaining injected recognition and no downloads; its singleton survives cases in one Robolectric sandbox. Coil decode diagnostics remain available, and early analyzer exit cannot stall the cancellation scenario.
- First OCR coordinator case now passes through real Coil decode and both fallback variants. Remaining initialization failures identified shared SDK singleton lifetime and are repaired with the idempotent SDK entry point; root owns the next focused confirmation.
- Added actual background lease proof through an ordered diagnostic on the existing benchmark-only receiver: positive foreground count, zero on Home, same session identity plus changing frames on resume. No component/permission added. Benchmark window and device OCR tests keep the screen awake only during their scoped execution.

## Physical OCR finding and repair
- Root's actual device corpus passed Japanese horizontal/vertical, Chinese and Korean recognition. The Latin-only watermark was misread by Japanese crop recovery as `三RMARK 2026`, revealing a real false-positive policy gap.
- Recovery now rejects a lone CJK glyph fused into a long uppercase Latin token. This is scoped to fallback crops, with normal-pass acceptance unchanged; standalone glyphs, short acronyms, mixed-case brands and multi-glyph native phrases remain eligible.
- Focused policy controls include AIの力, PC版, iPhone版, Googleさん, HDMI用 and separated bilingual labels. Coordinator tests prove both rejection across all recovery passes and acceptance of a normal mixed-script label without extra scanning. Original device watermark fixture remains unchanged for the rerun.
