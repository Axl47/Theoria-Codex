# Working List

## Current Task: Viewer CJK OCR Translation ExecPlan

### In Progress

- None.

### Pending

- None.

### Done

- [x] Read the task-orchestrator workflow and `.docs/PLANS.md` in full. Verification: required plan format, living-log sections, recovery guidance, and authoring checklist captured before plan edits.
- [x] Initialized this task-specific working list before editing the ExecPlan.
- [x] Inspected Viewer, Settings, persistence, dependency, and test boundaries. Verification: traced `ViewerScreen` image success/fit/transform/gesture paths, `ViewerRoute` ownership, DataStore-backed `ViewerSettings`, Settings actions/owner, application container construction, CJK taxonomy metadata, and existing unit/device safety lanes.
- [x] Defined the OCR language/model lifecycle and ordered recognition fallback. Verification: the plan specifies durable enablement versus live readiness, explicit-only model downloads, metadata-first ordering, Japanese → Chinese → Korean fallback, first valid script-evidence stop, and non-GMS failure behavior.
- [x] Defined background Viewer analysis, overlay geometry, tap/loading/translation states, and failure behavior. Verification: the plan specifies current/static-only admission, resolution-aware start, bounded decode, stale identity, transformed highlights, inverse hit testing, screen-space cards, model preparation, success/failure timers, and gesture preservation.
- [x] Created `.docs/exec/viewer-cjk-ocr-translation.html` as a self-contained implementation plan. Verification: required purpose, status, context, phases, work plan, validation, living log, recovery, and appendix sections are present.
- [x] Validated HTML structure, repository references, commands, acceptance checks, and working-tree scope. Verification: strict tag-stack/anchor audit passed for 64,781 bytes, 11 unique section IDs, and 15 links; trailing-whitespace and `git diff --check` checks passed; only `working_list.md` and the new ExecPlan are changed.
