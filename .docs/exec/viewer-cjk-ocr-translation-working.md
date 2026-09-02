# Viewer CJK OCR Translation Follow-up

## In Progress

- None.

## Pending

- [ ] Perform isolated Debug-device acceptance when explicitly authorized.

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
