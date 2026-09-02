# Working List

## Current Task: Implement Pixiv and Gelbooru Related Posts After Likes

### In Progress

- None.

### Pending

- None.

### Done

- [x] Re-read the task-orchestrator workflow and the complete committed ExecPlan before source edits.
- [x] Reinitialized this implementation checklist and confirmed the worktree is clean at `80fa8d4` before feature changes.
- [x] Phase 1 — Added `RelatedPostsSourceAdapter`, bounded first-response Pixiv retrieval, source-owned Gelbooru HTML parsing, two-wide ordered hydration, and opt-in related provider health checks. Verification: `./gradlew :core-domain:test :core-sources:test --no-configuration-cache` passed twice, including the final health-contract patch.
- [x] Phase 2 — Added shared latest-wins related state and a canonical-index feed projection with hidden-seed placement plus grid/scroll/paging translation. Verification: focused `RelatedPostsStateTest` and `RelatedFeedProjectionTest` passed through `:app-logic:test`.
- [x] Phase 3 — Added the registry loader, typed Like outcome, exact shell-to-route post-commit handoff, Search/FYP request ownership, root clearing, and count-stable non-empty FYP synchronization. Verification: focused Likes, loader, Search ViewModel, and For You ViewModel tests passed through `:app:testDebugUnitTest`; Debug Kotlin compilation passed.
- [x] Phase 4 — Added the full-line projected shelf, shared `SearchResultCard` rendering, canonical Search scroll/FYP paging translation, duration input union, and static `RELATED` Viewer source. Verification: focused related app-logic/app tests, `ViewerRoutePolicyTest`, and `FeedAutoplayArchitectureTest` passed together; Debug Kotlin compilation passed.
- [x] Phase 5 — Completed host validation and durable guidance. Verification: all affected JVM suites, Android-test compilation, Debug assembly, app/app-logic Detekt, aggregate Kover, hotspot gate, Debug package identity, HTML/diff checks, and focused post-extraction tests passed. Core Detekt still reports only inherited frozen owners; no new related file is named.

### Needs Human Validation

- [!] Run the read-only Pixiv/Gelbooru related provider health steps with configured credentials. The current environment has no Pixiv token or Gelbooru user/API key.
- [!] In an isolated `com.theoriacodex.debug` install, validate shelf placement, Hide liked anchoring, rapid replacement, horizontal autoplay, retry, and static Viewer behavior. No APK was installed or launched during host validation.
