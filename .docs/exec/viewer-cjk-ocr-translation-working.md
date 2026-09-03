# Viewer CJK OCR Translation Follow-up

## In Progress

- [!] Install the updated isolated Debug APK and repeat the screenshot acceptance after explicit authorization.

## Pending

- [ ] Capture the updated Viewer and confirm the four columns render as one union highlight.

## Done

- [x] Read the task-orchestrator workflow without replacing the unrelated root `working_list.md`.
- [x] Inspected the connected SM-S926U read-only: Samsung exposes the translation service and settings action; `enja` is installed, while `enko` and `enzh` are absent.
- [x] Added combined OCR and source-to-English translation readiness, including Samsung language-pack detection.
- [x] Added language-specific Samsung Galaxy Store handoff and generic Android settings fallback.
- [x] Gated Viewer OCR highlights to languages whose OCR and translation resources are both ready.
- [x] Refresh translation-pack readiness when returning to the Settings tab.
- [x] Passed focused Settings/model/Viewer tests and Debug Kotlin compilation; the Detekt task completed successfully with its existing 10 type-resolution warnings, and diff integrity passed.

## Reopened

- [x] Captured the post-download failure: Samsung accepts `ja -> en`, then reports `translator is null`; all `enja`, `enzh`, and `enko` packs are installed.
- [x] Verified Samsung's standard `CustomTranslationService` advertises an empty capability set and only creates its neural translator from supported chat-room sessions, not ordinary app-created translation sessions.
- [x] Verified Samsung's separate `TranslationServiceForExternal` is protected by an additional Samsung AI Core package/feature allowlist despite its normal bind permission, so it is not a stable third-party fallback.
- [x] User selected self-hosted LibreTranslate for a personal/open-source app at `translate.axor.dev` and authorized Dokploy setup plus commits when needed.
- [x] Recovered the existing Dokploy MCP without exposing credentials by launching its stored stdio configuration manually. The configured `args` value is one combined string, so this active session did not attach its tools automatically.
- [x] Replaced OEM translation with a bounded HTTPS LibreTranslate client, restored language readiness to OCR-only status, removed Samsung pack/settings behavior, and added the Dokploy Compose handoff. Verification: Debug/main and unit-test Kotlin compilation pass; 31 focused Settings/model/Viewer/LibreTranslate tests pass after correcting one nested coroutine-test harness error.
- [x] Completed bounded repository validation. Verification: the full app unit suite, Debug assembly, and `com.theoriacodex.debug` identity guard pass; Detekt returns to its existing 10 type-resolution warnings after avoiding an additional generated `BuildConfig` reference; the pinned Compose definition normalizes successfully.
- [x] Prepared the validated self-hosted translation implementation and deployment handoff for the authorized Conventional Commit.
- [x] Created and deployed the pinned LibreTranslate service in `Personal → production`, added its Dokploy HTTPS route, and created the `translate.axor.dev` DNS record without modifying existing workloads.
- [x] Verified the live service behind the origin certificate: `/health` is OK, the catalog contains `en`, `ja`, `ko`, and `zh-Hans`, and all three CJK-to-English smoke requests returned `Hello` in roughly 2–3 seconds.
- [x] Corrected the Android Chinese source code from `zh` to LibreTranslate v1.9.6's runtime code `zh-Hans` and updated its contract test.
- [x] Verified the Cloudflare edge after the user enabled proxying: public resolvers return edge IPs, the certificate chain is trusted, `/health` succeeds, and `ja`, `zh-Hans`, and `ko` live translations succeed without TLS bypasses in roughly 1–2 seconds.
- [x] User acceptance confirmed live translation works and identified fragmented Japanese vertical text: adjacent right-to-left columns can remain separate ML Kit blocks, producing short low-context LibreTranslate requests.
- [x] Captured and inspected the connected SM-S926U at 1440×3120. Verification: the installed Debug package postdates `d646762`, and four adjacent vertical columns remain independently highlighted because each ends with Japanese/ASCII exclamation punctuation.
- [x] Reproduced the screenshot as a four-column normalized geometry fixture and removed punctuation as a grouping boundary while retaining all spatial safeguards.
- [x] Passed the corrected focused batch: the device-derived four-column regression, existing phrase-grouping/OCR policy coverage, Debug Kotlin compilation, and app-logic Detekt all pass.
- [x] Traced recognized ML Kit blocks into normalized `ViewerOcrRegion` polygons and defined the smallest policy boundary: Japanese-only tall-region grouping with bounded horizontal gap, vertical overlap, compact union, right-to-left ordering, whitespace collapse, and terminal-punctuation stops.
- [x] Implemented Japanese vertical-column grouping in `app-logic` and applied it once after ML Kit region normalization. Verification: adjacent columns merge into one union highlight and right-to-left phrase; vertical singleton whitespace is normalized.
- [x] Preserved uncertainty boundaries. Verification: terminal punctuation, large horizontal gaps, vertically separate bubbles, horizontal Japanese, Chinese, and Korean remain independent in focused policy tests.
- [x] Passed the bounded validation batch: focused OCR policy/grouping tests, Debug Kotlin compilation, and affected app/app-logic Detekt tasks complete successfully; app Detekt retains its existing 10 type-resolution warnings.
- [x] Updated the living ExecPlan and durable Viewer OCR contract, then prepared the validated grouping patch for its authorized Conventional Commit.
