---
created_at: 2026-02-24T18:08
updated_at: 2026-02-24T22:40
---
# Working List

## Pending
- [ ] Milestone 6: Implement Viewer + Codex + Save flows
- [ ] Milestone 7: Implement Settings, scenario controls, and offline lifecycle behavior
- [ ] Milestone 8: Validate end-to-end behaviors and update docs

## In Progress
- [~] Milestone 5: Implement Search + Explore UI flows with Draft/Apply behavior (completed: baseline screens, autocomplete include/exclude, filter bottom sheet staging, quick-query + trending handoff, unified status pills; remaining: viewer entry handoff, richer masonry visual fidelity, stricter state restoration)

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
