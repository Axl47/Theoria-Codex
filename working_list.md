# Working List

## Current Task: Preserve Search Scroll And Bound Related Loading

### In Progress

- None.

### Pending

- None.

### Done

- [x] Re-read the task-orchestrator workflow and the complete related-posts ExecPlan.
- [x] Confirmed a clean baseline at `cccbe08` and isolated both causes: projection-driven synthetic scroll writes and an unbounded feature request spanning retrying HTTP attempts.
- [x] Made shelf entries non-persistable and moved Search restore/persistence effects into a stable projection-aware owner. Verification: source-level data flow now ignores shelf-first positions instead of synthesizing the seed.
- [x] Added a 15-second overall related-request deadline that publishes the existing retryable failure state while preserving route cancellation. Verification: timeout and cancellation branches are explicitly distinct in the shared controller.
- [x] Added focused regressions for latest-scroll preservation, timeout, and true cancellation. Verification: the regenerated focused app-logic/app batch passed 64 executed tasks; the first attempt reached compilation but Gradle could not read a missing cached test-results binary, so `--rerun-tasks` rebuilt it successfully.
- [x] Completed bounded host validation and durable documentation. Verification: app/app-logic Detekt plus aggregate Kover passed 194 tasks, the frozen hotspot gate passed with SearchScreen at 1,935/1,958 and SearchViewModel unchanged at 1,099/1,100, HTML/diff checks passed, and concurrent OCR files remain excluded from this task's commit.

### Needs Human Validation

- None yet.
