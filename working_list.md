# Testing audit remediation

- [~] Plan and coordinate all twelve findings; maintain `.docs/exec/testing-audit-remediation.html`.
- [x] F01 Real app journeys: Search/Viewer/restoration, Recents Multi-Search/FYP, collection controls.
- [x] F02 Profile create/switch/delete isolation, cancellation, last-profile and failure behavior.
- [!] F03 Physical performance comparator and realistic feed/duration workloads, metric documentation.
- [x] F04 Pre-install packaged identity/signature validation and host-only harness tests.
- [x] F05 Real-provider uniqueness, complete request journeys, missing adapter operations.
- [x] F06 Strengthen Undo/order/request/batch assertions and targeted mutation evidence.
- [x] F07 Replace brittle behavior-shaped source assertions and clarify test scope.
- [x] F08 On-disk production Room migration chain and close/reopen.
- [x] F09 Actual OCR coordinator/recognizer/crop pipeline and transport boundaries.
- [x] F10 Translation gateway handler boundary/failure/concurrency tests and CI execution.
- [x] F11 App behavior coverage gate, device triggers, minified runtime acceptance.
- [x] F12 Adverse grouped fallback pagination, ordering, deduplication, cancellation.
- [x] Host validation, sequential lint/static/coverage checks, package safety checks, functional device and minified acceptance passed.
- [~] Evidence and developer guidance updated; physical benchmark validation/calibration remains pending.

## Ownership
- providers_storage: core-domain, core-sources, core-data-android tests/production repairs only; F05/F08/F12 + ordering assertion.
- user_flows: app Search/Recents/Codex/Settings/navigation production/test files and integrated journey fixture; F01/F02/Undo. Do not edit Gradle/workflows/quality files.
- media_performance: app media/viewer production/tests, app-logic media policy tests, macrobenchmark and benchmark fixture files; F03/F09/batching/media guards. Do not edit Gradle/workflows/scripts.
- root: scripts, gateway, Gradle/workflows, remaining structural guards, docs/plans and combined validation.

No live services or production package operations. Connected work requires host-only graph and packaged identity/signature evidence first. Physical performance claims require fresh comparable runs; an unavailable device is an explicit evidence gap, not a pass.

## Verified phase evidence
- Gateway: 14 HTTP/upstream tests passed; 63 Python helper tests passed (including comparator, scope and pre-install rejection).
- Domain/provider/Room host suites passed after realistic repeated-ID fixture correction and production-factory upgrade tests.
- Profile/Undo and actual translation transport cancellation focused tests now pass. OCR coordinator fixture decode cause remains under investigation; bounded tests fail rather than hang.
- All Android/benchmark/acceptance Kotlin sources compiled; packaged assembly underway before device preflight.

- Repaired app device subset: 14/16 passed, then remaining Search/FYP journeys 4/4 passed after real per-session Viewer recording fix and bounded sheet readiness wait. All real CJK corpus cases including unchanged Latin watermark now pass.
- Three compile-preserving core mutants (ordering, deduplication, filtered-page continuation) were caught by assertions; both production files restored byte-for-byte.

## Remaining external dependency (2026-09-07)
- ADB no longer lists the authorized phone. The performance wrapper rejected preflight before starting any benchmark. User was asked to reconnect.
- F03 code and seven-scenario minified artifact are ready, but the new physical workloads have NOT yet been validated or calibrated. Need three distinct full same-artifact baseline runs and one fresh comparison; fix any revealed workload problems before claiming completion.
- Profiles regenerated on the phone successfully (3m33s); final minified APKs built with no missing-startup-entry warnings. Minified acceptance passed write/read across actual process restart. Lint and Detekt passed.
- Final host reports: 1,179 cases, 0 failures/errors, 3 intentional live-source skips. Python: 64 helper and14 gateway tests passed. Changed executable coverage:118/122 (96.72%). Six deliberately broken core/gateway behaviors were caught and source restored.
