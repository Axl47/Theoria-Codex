# Gelbooru Search Phase 1: Source Mode, Suggestion-Gated Tags, and Unified Compatibility Mapping

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan must be maintained according to `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`.

## Purpose / Big Picture

After this change, users can search Gelbooru directly from Search mode (instead of Pixiv-only runtime visibility), use live tag autocomplete backed by source adapters, and avoid invalid Gelbooru typed tags by only allowing typed additions that match suggested tags. In Unified mode, Gelbooru receives compatibility-mapped tags (first autocomplete match per user tag) so Gelbooru participation is less brittle when raw user tags are not Gelbooru-native. Users can also paste Gelbooru credential snippets in the API key field (`&api_key=<key>&user_id=<id>`) and have both fields populated automatically.

## Progress

- [x] (2026-02-26 00:00Z) Capture locked decisions and implementation milestones from planning session.
- [x] (2026-02-26 00:01Z) Create this ExecPlan and refresh `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`.
- [x] (2026-02-26 00:12Z) Implement runtime source exposure update (Pixiv + Gelbooru) in app wiring.
- [x] (2026-02-26 00:19Z) Implement source-driven autocomplete pipeline and Gelbooru typed-tag gating in Search.
- [x] (2026-02-26 00:20Z) Add Unified per-source query override support and Gelbooru compatibility mapping logic.
- [x] (2026-02-26 00:22Z) Implement credential paste parser and wire it into Settings field handlers.
- [x] (2026-02-26 00:24Z) Add/adjust unit tests for orchestrator overrides, coordinator behavior, parser parsing, and canonical Gelbooru page URL assertions.
- [x] (2026-02-26 00:25Z) Update README with user-visible behavior changes.
- [x] (2026-02-26 00:27Z) Run validation commands and record outcomes.

## Surprises & Discoveries

- Observation: Runtime currently wires `RealAdapterRegistry` with `exposedSources = setOf(SourceKey.PIXIV)` so Gelbooru exists in adapters but is intentionally hidden at app runtime.
  Evidence: `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` registry construction.

- Observation: Android `Uri` parsing path for credential extraction did not produce values reliably in local JVM unit tests.
  Evidence: Initial `GelbooruCredentialInputParserTest` failures (`requireNotNull` hit null parse result) during `:app:testDebugUnitTest`; resolved by switching parser to pure query-string parsing.

## Decision Log

- Decision: Keep canonical Gelbooru post URLs as `index.php`.
  Rationale: Adapter already emits `index.php`, and this is the locked direction for canonical sharing and persistence.
  Date/Author: 2026-02-26 / Codex

- Decision: In Unified mode, failed compatibility lookups for Gelbooru tags fall back to the original user tag instead of excluding Gelbooru.
  Rationale: Preserves search continuity and avoids hard failures when autocomplete does not return a match.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep two credential fields in Settings, but parse combined snippets pasted into API key input.
  Rationale: Backward-compatible UI with faster credential entry from Gelbooru account pages.
  Date/Author: 2026-02-26 / Codex

- Decision: Parse Gelbooru credential snippets with pure query-string parsing instead of Android `Uri`.
  Rationale: Keeps parsing deterministic in local unit tests and runtime, and handles raw fragments/full URLs with the same logic.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented all scoped Gelbooru Search Phase 1 changes: runtime source exposure now includes Gelbooru, search autocomplete is adapter-driven with fallback to cached suggestions, Gelbooru source-mode typed tag input is suggestion-gated, Unified search supports per-source query overrides and applies Gelbooru compatibility tag mapping, and Settings now parses pasted Gelbooru API/user snippets in the API key field. Added unit coverage for orchestrator overrides, coordinator gating/mapping behavior, parser correctness, and canonical Gelbooru `index.php` page URL invariant.

Validation outcome:

- `./gradlew :core-domain:test :core-sources:test :app:testDebugUnitTest` passed.
- `./gradlew assembleDebug` passed.

## Context and Orientation

The runtime app shell is in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app`. Search behavior is coordinated by `SearchCoordinator` and rendered by `SearchScreen`. Unified search orchestration and source statuses live in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain`, while concrete source adapters (including Gelbooru) live in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources`.

Key files for this change:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` (runtime source exposure, Settings wiring)
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt` (autocomplete state, validation, unified overrides)
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` (typed input behavior, debounce-driven autocomplete requests)
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt` (per-source query override support)
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt` (Gelbooru credential helper text)
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt` (canonical URL invariant)

## Plan of Work

First, expose Gelbooru at runtime by updating the app’s `RealAdapterRegistry` wiring so Search mode options include Gelbooru. Next, move autocomplete from local trending filtering to active adapter-driven lookups that run as the user types. The coordinator will own autocomplete results and typed-input validation state; `SearchScreen` will request suggestions with debounce and use coordinator-provided validation to gate typed additions in Gelbooru source mode.

Then, introduce per-source query overrides in `UnifiedSearchOrchestrator.search(...)` so Unified runs can keep one global query while selectively rewriting Gelbooru tags. `SearchCoordinator` will precompute a Gelbooru compatibility query from autocomplete first matches and retain it for pagination continuity.

After search-path changes, add a small parser utility for Gelbooru credential snippets and wire it into the API key field handler so paste flows auto-populate user ID and API key.

Finally, add tests for new behaviors, update README for user-visible behavior, and run validation commands.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Edit runtime wiring and Search flow files:
   `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`
   `app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`
   `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`
   `core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt`
   `app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt`

2. Add parser utility + tests:
   `app/src/main/java/com/theoriacodex/app/sourceauth/GelbooruCredentialInputParser.kt`
   `app/src/test/java/com/theoriacodex/app/sourceauth/GelbooruCredentialInputParserTest.kt`

3. Add/update behavior tests:
   `app/src/test/java/com/theoriacodex/app/search/SearchCoordinatorTest.kt`
   `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestratorTest.kt`
   `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`

4. Update docs:
   `README.md`

## Validation and Acceptance

Run:

- `./gradlew :core-domain:test :core-sources:test :app:testDebugUnitTest`
- `./gradlew assembleDebug`

Acceptance outcomes:

- Search mode row includes Gelbooru source option.
- In Gelbooru source mode, typed tags can only be added when they match suggestions (include and exclude forms).
- Unified mode still searches all enabled sources, and Gelbooru receives compatibility-mapped include/exclude tags with fallback to original tags when no match exists.
- Pasting `&api_key=<key>&user_id=<id>` into Gelbooru API Key input auto-fills both API key and user ID fields.
- Existing Gelbooru canonical post URL format remains `index.php`.

## Idempotence and Recovery

Changes are additive and local to runtime search, settings input handling, and orchestration parameters. If tests fail during implementation, re-run targeted modules first (`:core-domain:test`, `:app:testDebugUnitTest`, `:core-sources:test`) before full validation. No data migration is required because existing credential storage keys remain unchanged.

## Artifacts and Notes

Implementation will keep command outputs in task notes and summarize final outcomes in this plan’s `Outcomes & Retrospective` section once complete.

## Interfaces and Dependencies

`UnifiedSearchOrchestrator.search(...)` will gain a new optional argument:

    queryOverridesBySource: Map<SourceKey, Query> = emptyMap()

`SearchCoordinator` will add source-driven autocomplete state and typed-input validation helpers for Gelbooru source mode, while preserving existing `addIncludeTag` / `addExcludeTag` use from suggestion panel interactions.

`parseGelbooruCredentialInput(raw: String): GelbooruCredentials?` will be introduced in app layer and used by the API key input handler to support raw snippet, query-fragment, and full URL parsing.

Revision Note (2026-02-26): Initial creation to execute Gelbooru Search Phase 1 under PLANS.md living-plan requirements.
Revision Note (2026-02-26): Updated progress, discoveries, decisions, and outcomes after full implementation and validation pass.
