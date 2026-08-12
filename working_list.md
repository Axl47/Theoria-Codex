# Working List

## Current Task: Benchmark-Guided Refactor Program

### In Progress

- None.

### Pending

- None.

### Done

- [x] Split the program into independently reviewed commits for interaction acceptance, duration measurement, Viewer state, repository boundaries, app shell, provider policies, profile safety, and generated profiles. Evidence: each index snapshot passed `git diff --cached --check` and was inspected before commit.
- [x] Completed all six phases in `.docs/exec/benchmark-guided-architecture-refactors.html`: honest duration makespan, media-scoped Viewer transform, production/test repository separation, bounded app-shell extraction, provider policy extraction, and refreshed compiler profiles.
- [x] Repaired and guarded the baseline-profile test/target package collision found by connected collection. Evidence: target `com.theoriacodex.baselineprofile`, test APK `com.theoriacodex.baselineprofile.test`; generation passed 2/2 on SM-S926U without touching production.
- [x] Passed the bounded final host lane: all app/module unit suites, benchmarkRelease compilation, Android lint, every configured Detekt owner, hotspot gate, 0.58% duplication gate, workflow lint, and `git diff --check`.
- [x] Completed exact-final physical proof on SM-S926U / Android 16. Evidence: 5/5 tests, zero failures/errors/skips, 35 traces, 24/24 Search and duration first frames, 10/10 Viewer first frames, one duration batch span per iteration, JSON SHA-256 `16778f3b8600e818972522fe80f246a6f850ffff97caac574e009e4de8393190`.

- [x] User completed all outstanding user-facing interaction groups on the v0.10 build and reported acceptance. Evidence: direct confirmation on 2026-08-12; the plan-by-plan documentation sync is complete.
- [x] Updated every current and historical ExecPlan that still presented a user-facing interaction group as pending. Evidence: 16 plans now record user-reported acceptance on 2026-08-12; the non-user fault-injection and separate numeric/live-provider gaps remain unclaimed.
- [x] Proved the connected benchmark safety boundary before device work. Evidence: dry-run orders `:app:verifyBenchmarkReleaseInstallableApplicationId` before the connected task; packaged app is `com.theoriacodex.benchmark` in `:benchmarkFixture`; runner has no side-effect listener configuration; physical target is SM-S926U / Android 16.
- [x] Ran the exact-v0.10.0 physical Macrobenchmark and preserved the complete output directory. Evidence: 5 tests, 0 failures/errors/skips, 35 Perfetto traces plus JSON, 3.0 GiB, JSON SHA-256 `5601840e21aa919fc63e7586150d725bf3a75bf4341a5e2edf425ef5c4eab36a`.
- [x] Compared the frozen and exact-v0.10.0 runs and completed the benchmark-triggered audit. Evidence: startup regressed 10.39% cold / 21.09% warm; Search and Viewer remained broadly stable; duration workload totals changed semantics; stale generated profiles produced 736 missing-entry warnings.
- [x] Created `.docs/exec/benchmark-guided-architecture-refactors.html` with six bounded phases, explicit safety gates, preserved autoplay/navigation constraints, and an evidence-backed deferral of the route-wide viewport rewrite.
- [x] Confirmed the existing Search and Codex convergence points; no parallel screen-specific owners are needed. Evidence: `SearchCoordinator`, `Query`, `RoomCodexLikesRepository`, and `CodexActionSheet` own the relevant flows.
- [x] Created `.docs/exec/grouped-search-tags-and-automatic-rules.html` with the agreed data flow, UI states, failure behavior, migration direction, and bounded validation plan.
- [x] Probed provider OR behavior without exposing credentials. Evidence: Gelbooru `{landscape ~ cat}` returned 40/40 posts satisfying one alternative; Pixiv rejected an ordinary control query as `invalid_grant`, so Pixiv remains on the exact fallback path. Added and passed `ProviderHealthCheckerTest` coverage for aggregate OR verification.
- [x] Added grouped positive Search terms, stable v3 query hashes, Saved State/Recents compatibility, applied-query replay, and collapsed/Recent expression summaries.
- [x] Added Gelbooru-native grouped OR and bounded exact fallback execution with canonical filtering, deduplication, sort-owned merging, and independent branch continuation.
- [x] Added compact Search group chips and the shared group editor for OR alternatives, term removal, group removal, and splitting an alternative back into a required group.
- [x] Removed repetitive `AND` labels from the editable Search tag row while retaining explicit OR labels inside alternative groups.
- [x] Replaced stacked Automatic Rules source tag sections with a source-name dropdown that renders one provider's available tags at a time.
- [x] Canonicalized Automatic Rule numbering per source so deleting an entire group shifts every later group down immediately and durably.
- [x] Added a local filter field for the selected Automatic Rules source, including space/underscore matching, clear action, and distinct empty-result messaging.
- [x] Added source-scoped grouped Codex Automatic rules, Room schema 7 migration, JSON compatibility, atomic like-time matching, and AND/OR editing in the existing collection-action sheet.
- [x] Completed host validation: relevant Gradle unit suites, Android-test source compilation, app compilation, Detekt, `git diff --check`, and ExecPlan HTML parsing all pass.
