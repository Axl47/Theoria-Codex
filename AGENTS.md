---
created_at: 2026-02-24T17:48
updated_at: 2026-02-25T02:34
---
# THEORIA CODEX AGENTS DOCUMENT

## ExecPlans

When writing complex features or significant refactors, use an ExecPlan (as described in `.agent/PLANS.md` or the repo root `PLANS.md`) from design to implementation.

## Development Details

Whenever new updates are made, this file (`AGENTS.md`) should be updated with any surprising files not apparent from the codebase. Additionally, the `README.md` file should be updated with any new features or changes the app receives that are not simple fixes, so that users can easily see what's new without having to read through the codebase.

## Surprising Files Added

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`: living execution plan for the MVP implementation.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`: task-orchestrator live checklist used during implementation execution.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/query/QueryHash.kt`: deterministic query identity utility for restoration and apply-state logic.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt`: in-memory persistence/cache scaffolding used to integrate feature work before Room/DataStore.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`: JSON file-backed repository implementations for Codex/query/settings/cache state persistence.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/main/resources/stubs/`: fixture corpus for source paging, trending tags, and scenario simulation.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt`: capability-aware unified multi-source search orchestration and weighted interleave.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`: shared Search/Explore query coordinator implementing Draft/Applied behavior against stub orchestration.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt`: functional Search + Explore slice including autocomplete, filter sheet staging, quick-query handoff, and status states.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: expanded contract surface adding `CodexSortMode`, explicit settings setters, `ViewerLaunchContext`, and `UiRestoreRepository`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerState.kt`: fullscreen viewer interaction/state implementation with gesture logic and info/actions sheet.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/CodexListScreen.kt`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/SaveToCodexSheet.kt`: complete Codex management/save flows.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt`: runtime settings controls wired to source toggles/weights/cache/scenario behavior.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/search/SearchCoordinatorTest.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/viewer/ViewerStateTest.kt`: new app-level tests for search restoration semantics and viewer state transitions.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/theme/TheoriaTheme.kt`: centralized Material 3 night-focused color scheme applied across the Compose app shell.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/`: real source integration module containing Pixiv/AIBooru/Gelbooru adapters, HTTP transport, and `RealAdapterRegistry` runtime wiring.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapterRegistry.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceFailure.kt`: registry abstraction and typed source failure contract used by orchestrator/status UI.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/`: secure source credential store (Android Keystore-backed encrypted preferences) plus Pixiv PKCE authorization controller.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/AndroidManifest.xml`: includes network permission and Pixiv auth callback intent-filters for both `theoriacodex://pixiv-auth/callback` and `pixiv://account/login`.

## Final Output

When asking the user to verify implemented changes, output a checklist they can fill to make sure everything works as intended. Describe what they should see, how it should work, and keep in mind the possible checkboxes types (`[x]` is completed, `[~]` is partial, `[ ]` is not completed/not working). The user will then fill in the checklist and provide feedback on any issues they encounter, which can be used to further refine the implementation.

Occasionally, remind the developer of the commands they need to use to test the changes, lest they run the run command and forget to build before then. For example, if they need to run `npm run build` before `npm run start`, remind them of this in the final output instructions.
