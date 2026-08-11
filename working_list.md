# Working List

## Current Task: Grouped Search Tags And Automatic Rules

### In Progress

- None.

### Pending

- [ ] Optional isolated Debug device acceptance for the final touch interaction and visual density; no connected task or install was run during implementation.

### Done

- [x] Confirmed the existing Search and Codex convergence points; no parallel screen-specific owners are needed. Evidence: `SearchCoordinator`, `Query`, `RoomCodexLikesRepository`, and `CodexActionSheet` own the relevant flows.
- [x] Created `.docs/exec/grouped-search-tags-and-automatic-rules.html` with the agreed data flow, UI states, failure behavior, migration direction, and bounded validation plan.
- [x] Probed provider OR behavior without exposing credentials. Evidence: Gelbooru `{landscape ~ cat}` returned 40/40 posts satisfying one alternative; Pixiv rejected an ordinary control query as `invalid_grant`, so Pixiv remains on the exact fallback path. Added and passed `ProviderHealthCheckerTest` coverage for aggregate OR verification.
- [x] Added grouped positive Search terms, stable v3 query hashes, Saved State/Recents compatibility, applied-query replay, and collapsed/Recent expression summaries.
- [x] Added Gelbooru-native grouped OR and bounded exact fallback execution with canonical filtering, deduplication, sort-owned merging, and independent branch continuation.
- [x] Added compact Search group chips and the shared group editor for OR alternatives, term removal, group removal, and splitting an alternative back into a required group.
- [x] Removed repetitive `AND` labels from the editable Search tag row while retaining explicit OR labels inside alternative groups.
- [x] Replaced stacked Automatic Rules source tag sections with a source-name dropdown that renders one provider's available tags at a time.
- [x] Canonicalized Automatic Rule numbering per source so deleting an entire group shifts every later group down immediately and durably.
- [x] Added source-scoped grouped Codex Automatic rules, Room schema 7 migration, JSON compatibility, atomic like-time matching, and AND/OR editing in the existing collection-action sheet.
- [x] Completed host validation: relevant Gradle unit suites, Android-test source compilation, app compilation, Detekt, `git diff --check`, and ExecPlan HTML parsing all pass.
