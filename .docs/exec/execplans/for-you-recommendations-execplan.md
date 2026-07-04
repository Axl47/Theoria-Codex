# Personalized For You Feed With Two Local Profiles

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository includes `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`; this document must be maintained in accordance with it.

## Purpose / Big Picture

After this change, users can heart posts to teach a local recommendation memory and browse a new top-level `For You` tab that auto-loads results based on liked tags. Recommendation memory is stored per source and per local profile so Pixiv and Gelbooru preferences stay source-correct, and two people sharing the same device can keep independent tastes.

The user-visible outcome is straightforward: heart some items in Search, open `For You`, and see a browsing-first feed that reflects those likes. Switch profile and the feed and heart state should change. Restarting the app should preserve both profiles, likes, and feed behavior.

## Progress

- [x] (2026-02-27 00:00Z) Authored ExecPlan and aligned design to per-source tag sets in Unified mode.
- [x] (2026-02-27 02:10Z) Implemented profile support (`USER_1`/`USER_2`) in settings contracts, persistence, and Settings UI.
- [x] (2026-02-27 02:20Z) Implemented local likes persistence repository (in-memory + file-backed) with tests.
- [x] (2026-02-27 02:33Z) Implemented recommendation/tag-set generator in `:core-domain` tuned for small datasets.
- [x] (2026-02-27 02:43Z) Added heart button overlays to Search cards and wired likes to active profile.
- [x] (2026-02-27 02:55Z) Added `For You` tab, coordinator, screen, and Viewer live-pagination binding.
- [x] (2026-02-27 03:00Z) Added likes-management controls to Settings (profile like count + clear active profile likes).
- [x] (2026-02-27 03:02Z) Updated `README.md` and `AGENTS.md` with new behavior/files.
- [ ] (2026-02-27 03:03Z) Run validation commands and record outcomes (completed: `:core-domain:test`, `:core-data:test`, `:app:testDebugUnitTest`, `assembleDebug`; remaining: `lintDebug` fails on pre-existing Media3 `@UnstableApi` lint errors in `app/.../viewer/ExoVideoComponents.kt`).

## Surprises & Discoveries

- Observation: `SearchResultCard` is reused in Codex detail.
  Evidence: `app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt` imports and calls `SearchResultCard(...)`.

- Observation: settings persistence uses Gson with Kotlin data classes loaded from existing files.
  Evidence: `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` `SettingsStoreFile` is deserialized via `Gson.fromJson` and converted through `toDomain()`.

- Observation: unified search already supports per-source query overrides.
  Evidence: `SearchCoordinator` calls `UnifiedSearchOrchestrator.search(... queryOverridesBySource = ...)`.

- Observation: Viewer load-more callback lambdas returning `Job` break type inference for function-typed branches in `when` expressions.
  Evidence: Kotlin compile error in `TheoriaApp.kt` around `onLoadMoreFromSource` until lambdas explicitly return `Unit`.

- Observation: `lintDebug` currently fails due existing Media3 opt-in lint violations unrelated to this feature.
  Evidence: Lint report first error points to `app/src/main/java/com/theoriacodex/app/viewer/ExoVideoComponents.kt` requiring `@UnstableApi` opt-in.

## Decision Log

- Decision: Store recommendations and hearts by active local profile (`USER_1`/`USER_2`).
  Rationale: Requirement explicitly targets two users sharing one install.
  Date/Author: 2026-02-27 / OpenCode

- Decision: In Unified mode, generate per-source tag sets and pass them through `queryOverridesBySource`.
  Rationale: Keeps source vocabularies independent and prevents cross-source tag mismatch failures.
  Date/Author: 2026-02-27 / OpenCode

- Decision: Recommendation queries prefer single tags at low sample sizes and only use tag pairs when confidence is meaningful.
  Rationale: Pair-only search over-constrains booru/Pixiv queries and performs poorly with sparse like history.
  Date/Author: 2026-02-27 / OpenCode

## Outcomes & Retrospective

Implemented the full feature slice: two local profiles in settings, persistent per-profile likes memory, source-aware recommendation tag-set generation, heart toggles in Search cards, a new `For You` top-level tab with unified per-source query overrides, and Viewer pagination support for `FOR_YOU` sessions.

Validation status:

- Passed: `./gradlew :core-domain:test :core-data:test`
- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew assembleDebug`
- Not yet green: `./gradlew lintDebug` (currently fails on pre-existing Media3 `@UnstableApi` lint errors outside this feature scope)

## Context and Orientation

Current relevant modules and files:

- Search state and execution live in `app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`.
- Search UI and reusable cards live in `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`.
- Navigation and dependency wiring live in `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`.
- Top-level tabs now include Search/For You/Explore/Codex/Settings in `TopLevelDestination`.
- Settings contracts and persistence live in:
  - `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`
  - `core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt`
  - `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`
- Unified merge orchestration lives in `core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt`.

The implementation will add a new likes repository and recommendation generator while reusing existing source adapters, source weights, and unified pagination mechanics.

## Plan of Work

First, add two-profile support to app settings so all recommendation operations can resolve an active profile. Then create a `LikesRepository` with file-backed persistence and tests.

Next, add a recommendation generator in `:core-domain` that computes source-local tag affinity using liked posts as documents. The generator will bias toward reliable single tags and only attach a second tag when co-occurrence metrics exceed thresholds. Thresholds must scale for small populations (two-user shared app scenario), which means low starting minima and gradual increases.

Then implement app runtime wiring: heart overlays on Search cards, new `For You` top-level tab and coordinator, per-source query generation, unified execution with query overrides, and viewer pagination handoff for `FOR_YOU` sessions.

Finally, add settings controls for active profile and clearing likes, update docs, run tests/build, and record results in this file.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Profile settings support

   - Update `core-data/.../Repositories.kt`:
     - Add `UserProfile` enum.
     - Add `activeProfile` to `AppSettings`.
     - Add `setActiveProfile(profile: UserProfile)` to `SettingsRepository`.
   - Update in-memory and file-backed settings repositories accordingly.
   - Extend settings persistence tests.
   - Update Settings UI + app wiring to switch active profile.

2. Likes repository

   - Add likes domain/data contracts in `core-data/.../Repositories.kt`.
   - Implement `InMemoryLikesRepository` and `FileBackedLikesRepository`.
   - Persist likes to `files/theoria_codex/likes_store.json`.
   - Add tests for toggle, clear, profile isolation, and restart persistence.

3. Recommendation engine

   - Add `core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/` package.
   - Implement tag-frequency + pair-frequency scoring and query generation.
   - Include thresholds that scale with sample size and behave well for low counts.
   - Add deterministic unit tests with seeded random.

4. Search hearts + For You feature

   - Add optional heart overlay support to `SearchResultCard`.
   - Wire Search screen to show/toggle hearts using active profile likes.
   - Add `For You` tab route and screen.
   - Implement `ForYouCoordinator` that:
     - reads likes for active profile,
     - builds per-source query overrides,
     - executes unified search with source weights,
     - supports pagination and refresh/shuffle.
   - Add `ViewerStreamSource.FOR_YOU` and bind viewer pagination for For You sessions.

5. Docs and validation

   - Update `README.md` with For You + hearts + two-profile behavior.
   - Update `AGENTS.md` surprising files list for new modules/stores.
   - Run:
     - `./gradlew :core-domain:test :core-data:test`
     - `./gradlew testDebugUnitTest`
     - `./gradlew assembleDebug`
     - `./gradlew lintDebug`

## Validation and Acceptance

Automated acceptance:

- All commands in Concrete Steps section 5 pass.
- New/updated unit tests pass in `:core-domain`, `:core-data`, and `:app`.

Manual acceptance:

- In Search, tapping heart toggles liked state instantly and persists after restart.
- `For You` shows empty-state guidance before any likes.
- After liking posts, `For You` loads related content and paginates.
- Switching profile in Settings changes heart state and recommendation memory.
- Clearing likes in Settings only clears active profile data.

## Idempotence and Recovery

- If settings or likes files are missing, repositories must recreate defaults.
- If likes file is malformed, implementation should recover to empty likes instead of crashing.
- New settings fields must deserialize safely from older persisted files.

## Artifacts and Notes

- New persisted file expected: `files/theoria_codex/likes_store.json`.
- Recommendation history remains fully local; no server-side personalization is introduced.

## Interfaces and Dependencies

Required interfaces/types after implementation:

- `UserProfile` enum in `core-data`.
- `activeProfile` in `AppSettings` + `setActiveProfile(...)` in `SettingsRepository`.
- `LikesRepository` contract + in-memory + file-backed implementations.
- Recommendation generator in `core-domain` that outputs include tags per source.
- New app coordinator/screen package for For You in `app/src/main/java/com/theoriacodex/app/recommend/`.

Revision note (2026-02-27): Updated this plan after implementation to record completed milestones, validation outcomes, and newly discovered compile/lint constraints so future contributors can resume with current project state.
