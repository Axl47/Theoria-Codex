---
created_at: 2026-02-24T18:08
updated_at: 2026-02-25T01:42
---
# Working List

## Pending
- [ ] Non-critical visual polish follow-ups (masonry visual fidelity and richer image rendering)

## In Progress
- [~] Manual on-device smoke validation by developer checklist

## Done
- [x] Read `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/TheoriaSpec.md` and synthesize a multi-agent implementation strategy
- [x] Create ExecPlan and begin execution immediately after plan creation
- [x] Scaffold Android multi-module project locally with Gradle wrapper and app shell (`app`, `core-domain`, `core-data`, `core-stubs`)
- [x] Verify local build baseline with `./gradlew tasks --all` and `./gradlew assembleDebug`
- [x] Complete Milestone 2 domain/query contracts and tests (`QueryHash`, state machine, capability gate)
- [x] Complete Milestone 3 persistence/cache contract layer with in-memory + file-backed implementations and tests
- [x] Complete Milestone 4 stub fixtures, scenario-aware adapters, and unified weighted orchestrator with tests
- [x] Implement Milestone 5 Search + Explore functional baseline and interaction upgrades
- [x] Update `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/AGENTS.md` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/README.md` for new architecture/features
- [x] Re-run validation after milestone work with `./gradlew testDebugUnitTest assembleDebug :core-domain:test :core-stubs:test :core-data:test`
- [x] Complete Milestone 5 spec-critical parity: viewer launch handoff with `ViewerLaunchContext`, reset behavior hardening, query-hash scroll restoration wiring
- [x] Complete Milestone 6: implement Viewer + Codex list/detail + Save sheet and wire Search->Viewer->Save->Codex flow
- [x] Complete Milestone 7: implement Settings runtime controls (source toggles/weights, cache controls, scenario preset) with immediate runtime reflection
- [x] Complete Milestone 8: add unit tests for viewer state, search restoration semantics, codex sorting/dedup, settings normalization/scenario + run full validation gate
- [x] Validation passed: `./gradlew :core-domain:test :core-stubs:test :core-data:test`
- [x] Validation passed: `./gradlew testDebugUnitTest`
- [x] Validation passed: `./gradlew assembleDebug`
- [x] Validation passed: `./gradlew lintDebug`
- [x] Execute second-pass source cutover: add `SourceAdapterRegistry`, typed `SourceFailureReason`, and extend `SourceAdapter` contract with autocomplete + credential capability.
- [x] Add new runtime module `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources` with Pixiv/AIBooru/Gelbooru adapters and `RealAdapterRegistry` (Phase A exposure: Pixiv only).
- [x] Replace app runtime stub wiring with real registry in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`.
- [x] Implement secure source credential storage and auth orchestration in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/` (encrypted store + Pixiv PKCE callback flow).
- [x] Add minimal Settings “Source Accounts” controls (Pixiv connect/disconnect, Gelbooru credential save/clear) and dynamic available-source lists for Search/Settings.
- [x] Validation passed after cutover:
  - `./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test`
  - `./gradlew testDebugUnitTest`
  - `./gradlew assembleDebug`
  - `./gradlew lintDebug`
