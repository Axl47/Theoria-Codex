# Audit remediation working list

## Completed implementation (2026-09-04)

The previous completed translation-statistics checklist remains below. Its original diff is preserved at `/tmp/theoria-pre-audit-implementation.patch`.

- [x] Plan and execute `.docs/exec/audit-remediation.html` with disjoint file ownership.
- [x] Storage: shared payload merge, atomic batch save, bounded summaries, targeted orphan cleanup/index.
- [x] Cleanup: observation-only Likes, unused reset removal, fixture-only policies.
- [x] Recommendations: remove unused affinity and prepare training once off Main.
- [x] Viewer: remove unused projections/progress propagation, deduplicate restore writes.
- [x] Feeds: bounded shared filtered pagination and explicit Continue.
- [x] Collections: profile-scoped summaries, deferred tags, main-safe transfer/save workflow.
- [x] Quality: behavioral replacements for selected source-string tests and unused helper removal.
- [x] Focused and aggregate host validation, final review and documentation; device limitation recorded.

Validation: new save/summary/Viewer tests passed; Room migration passes after schema9 assets rebuild. Domain sampling matched 1,920 prior outputs. Pure paging tests pass. Host summary SQL returns eight covers at up to 50,000 memberships with linear work. Final tests/static analysis/lint pass: 1,168 cases, zero failures, three opt-in skips; 58.18% aggregate and 98.47% working-tree changed-line coverage; 36 quality-helper tests pass. Debug APK built/verified. Release JSON verification preserved 245 required fields across 44 retained classes. Strict configuration-cache storage and reuse passed. No Android device is connected; physical performance and connected UI validation are unavailable.

# Previous completed work

## Pending

- None.

## In Progress

- None.

## Done

- [x] Initialize the task checklist
  - Replaced the completed translation-settings checklist before editing.
- [x] Define the translation statistics boundary and acceptance checks
  - Count successful uncached server phrases and their exact source characters; exclude cache hits and failures, and keep statistics writes best-effort.
- [x] Add durable translation usage counters
  - Added normalized, saturating phrase and character totals with backward-compatible schema-1 defaults and DataStore round-trip coverage.
- [x] Record successful uncached server translations
  - The cache owner records deduplicated fetched keys after cache persistence; callback failures do not fail translation.
- [x] Project and render translation statistics in Settings
  - Added a Translation Stats group with phrase and source-character totals.
- [x] Update focused tests and durable contracts
  - Updated repository, projection, coordinator, Settings source, Gson/R8 manifest, and durable developer guidance; focused tests pass.
- [x] Run the bounded validation batch
  - Repository, projection, cache-owner, Settings-source, and Gson contract tests pass with Debug compilation. App-logic Detekt passes; app and core-data Detekt contain only their existing 70 and 3 unrelated findings, with none in changed files.
