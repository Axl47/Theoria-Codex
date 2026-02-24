---
created_at: 2026-02-24T17:48
updated_at: 2026-02-24T22:34
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
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt`: first functional Search + Explore UI slice replacing placeholders.

## Final Output

When asking the user to verify implemented changes, output a checklist they can fill to make sure everything works as intended. Describe what they should see, how it should work, and keep in mind the possible checkboxes types (`[x]` is completed, `[~]` is partial, `[ ]` is not completed/not working). The user will then fill in the checklist and provide feedback on any issues they encounter, which can be used to further refine the implementation.

Occasionally, remind the developer of the commands they need to use to test the changes, lest they run the run command and forget to build before then. For example, if they need to run `npm run build` before `npm run start`, remind them of this in the final output instructions.
