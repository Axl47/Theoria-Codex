# Working List

## Current Task: Implement Viewer CJK OCR Translation

### In Progress

- None.

### Pending

- None.

### Done

- [x] Re-read the task-orchestrator workflow and the complete current ExecPlan before implementation.
- [x] Reinitialized this implementation checklist before source edits.
- [x] Phase 1 — Added backward-compatible Viewer OCR settings, atomic per-language persistence, unbundled ML Kit dependencies, explicit Google Play module state/download management, Settings UI, container wiring, R8 contract fields, and focused tests. Verification: `:core-data:test`, focused Settings/model-manager tests, and `:app:compileDebugKotlin` passed; the batch exposed and then proved the repair for a concurrent language-enable lost update.
- [x] Phase 2 — Added Android-free metadata/order/script/geometry policy, bounded Coil decode, sequential ML Kit recognition, trace spans, and a cancellable Android 12+ system translation gateway. Verification: `ViewerOcrPolicyTest` and Debug Kotlin compilation passed. Discovery: ML Kit Translation attribution conflicts with its adult-content branding restriction, so the living plan now uses the neutral platform translator and retains ML Kit only for OCR.
- [x] Phase 3 — Wired a dedicated navigation-scoped OCR owner to live model readiness, durable Viewer settings, current static media identity, resolved metadata, and successful Coil image delivery. Verification: focused owner/route/image-pipeline tests and Debug Kotlin compilation passed; tests cover delayed eligibility, stale media results, positive quality-upgrade stability, and immediate disable clearing.
- [x] Phase 4 — Added a shared transformed image/highlight layer, padded fit/zoom/pan hit testing, accessibility activation semantics, an unscaled below/above-clamped card, and preparing/translating/success/failure state with cache reuse and bounded timers. Verification: focused OCR policy, owner, overlay geometry, Viewer transform/image pipeline, and model-manager tests plus Debug compilation passed.
- [x] Phase 5 — Completed host validation and durable documentation. Verification: 106 app-logic, 112 core-data, and 538 app tests passed with zero failures/errors; Android-test compilation, Debug assembly, affected Detekt owners, hotspot gate, 0.57% duplication gate, both 241-field release JSON checks, HTML/diff checks, and Debug package identity `com.theoriacodex.debug` passed. No APK was installed or launched.

### Needs Human Validation

- [!] On an isolated Debug install, download the requested OCR models and validate real horizontal/vertical/stylized CJK pages plus OEM on-device translation. This was not claimed from host tests because it requires a compatible device, model downloads, and representative user media.
