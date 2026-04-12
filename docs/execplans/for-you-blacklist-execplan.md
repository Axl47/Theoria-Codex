---
created_at: 2026-03-02T22:11
updated_at: 2026-04-10T16:28
---
# For You Blacklist and Profile-Scoped Controls

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository includes `PLANS.md`; this document is maintained in accordance with it.

## Purpose / Big Picture

After this change, a user can reject the current `For You` seed directly from the feed by tapping a trash icon. The rejected tag set is blacklisted for the active profile, a new recommendation is requested immediately, and the same seed does not reappear for that profile. Settings also expose a profile-scoped blacklist section so users can review and remove hidden tag sets.

## Progress

- [x] (2026-03-02 22:05Z) Added profile-scoped For You blacklist model and Settings repository APIs.
- [x] (2026-03-02 22:20Z) Implemented in-memory + file-backed persistence/normalization for blacklist entries and profile removal cleanup.
- [x] (2026-03-02 22:32Z) Wired For You coordinator blacklist action (trash -> persist blacklist -> refresh) and blacklist-aware seed generation.
- [x] (2026-03-02 22:41Z) Added For You trash icon UI and Settings blacklist management section.
- [x] (2026-03-02 22:52Z) Validation complete (`:core-data:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`).

## Surprises & Discoveries

- Observation: For You seed generation currently emits one tag set per source per refresh, so “blacklist current recommendation” maps naturally to source-tag-set entries.
  Evidence: `ForYouCoordinator.buildSeedBySource(...)` returns `Map<SourceKey, List<String>>` and `seedSummaryBySource` mirrors that shape.

## Decision Log

- Decision: Persist blacklist in `AppSettings` as `Map<profileId, List<ForYouBlacklistEntry>>` rather than a separate repository.
  Rationale: Blacklist behavior is tightly coupled to profile and feed preferences already managed by settings storage.
  Date/Author: 2026-03-02 / OpenCode

- Decision: Canonicalize blacklist tag sets to lowercase sorted tags.
  Rationale: Prevent duplicate entries caused by order/casing differences (for example `cat + night` vs `Night + Cat`).
  Date/Author: 2026-03-02 / OpenCode

## Outcomes & Retrospective

Implemented profile-scoped For You blacklist persistence, feed-side blacklisting via trash action, refresh-on-blacklist behavior, and Settings visibility/removal controls.

Validation status:

- Passed: `./gradlew :core-data:test`
- Passed: `./gradlew :app:testDebugUnitTest`
- Passed: `./gradlew :app:assembleDebug`

## Context and Orientation

Relevant files are:

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt` for settings contracts and app settings data model.
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt` and `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` for runtime and persistent behavior.
- `app/src/main/java/com/theoriacodex/app/recommend/ForYouCoordinator.kt` for recommendation seed selection and refresh logic.
- `app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt` for feed actions and controls.
- `app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt` and `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` for UI wiring.

## Plan of Work

Add blacklist data contracts to settings, then persist and normalize entries in both repository implementations. Next, wire For You coordinator to read blacklist entries per active profile, block matching seeds, and provide a mutation for “blacklist current seed and refresh”. Finally, expose a trash action in For You UI and a Settings section to review/remove entries.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Update settings contracts and repositories.
2. Wire For You coordinator and screen interactions.
3. Wire Settings screen list + delete action.
4. Run:
   - `./gradlew :core-data:test`
   - `./gradlew :app:testDebugUnitTest`
   - `./gradlew :app:assembleDebug`

## Validation and Acceptance

- In `For You`, tapping trash blacklists the current seed and immediately loads a new recommendation.
- Previously blacklisted source/tag combinations do not reappear while browsing the same profile.
- In `Settings`, blacklist entries appear under Recommendation Profile controls and can be removed.
- Blacklist behavior is profile-scoped and persists after app restart.

## Idempotence and Recovery

Blacklist normalization is idempotent; repeated add/remove requests for the same canonical source/tag-set are safe no-ops. If persisted settings are missing/malformed, repositories recover with defaults and an empty blacklist.

## Artifacts and Notes

Post-implementation validation logs should be attached in future updates to this file.

## Interfaces and Dependencies

Expected interfaces after implementation:

- `ForYouBlacklistEntry` data model in `core-data` settings contracts.
- `SettingsRepository.addForYouBlacklistEntry(...)` and `SettingsRepository.removeForYouBlacklistEntry(...)`.
- `AppSettings.forYouBlacklistByProfile` persistence in both in-memory and file-backed settings repositories.
