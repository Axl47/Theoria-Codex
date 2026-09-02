# Viewer CJK OCR Translation Follow-up

## In Progress

- [~] Complete isolated Debug-device acceptance after explicit install authorization.

## Pending

- [ ] Install only `com.theoriacodex.debug` after explicit authorization and verify the Samsung language rows and Galaxy Store handoffs on-device.

## Done

- [x] Read the task-orchestrator workflow without replacing the unrelated root `working_list.md`.
- [x] Inspected the connected SM-S926U read-only: Samsung exposes the translation service and settings action; `enja` is installed, while `enko` and `enzh` are absent.
- [x] Added combined OCR and source-to-English translation readiness, including Samsung language-pack detection.
- [x] Added language-specific Samsung Galaxy Store handoff and generic Android settings fallback.
- [x] Gated Viewer OCR highlights to languages whose OCR and translation resources are both ready.
- [x] Refresh translation-pack readiness when returning to the Settings tab.
- [x] Passed focused Settings/model/Viewer tests and Debug Kotlin compilation; the Detekt task completed successfully with its existing 10 type-resolution warnings, and diff integrity passed.
