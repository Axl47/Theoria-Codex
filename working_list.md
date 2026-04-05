---
created_at: 2026-04-05T02:20:00Z
updated_at: 2026-04-05T04:32:00Z
---
# Working List

## Pending

## In Progress

## Done
- [x] Review the existing source, viewer, creator-profile, and deep-link architecture
- [x] Verify Iwara public API endpoints for search, resolve, tags, and creator videos
- [x] Create the Iwara ExecPlan and refresh implementation tracking
- [x] Implement `SourceKey.IWARA` enum wiring and cross-cutting source metadata updates
- [x] Add `IwaraSourceAdapter` and source-layer tests
- [x] Wire real/stub registries and add Iwara stub fixtures
- [x] Integrate Iwara into creator browsing, viewer lazy resolution, and download resolution
- [x] Add Iwara post deep links and app-level tests
- [x] Update README.md and AGENTS.md for the new source
- [x] Run Gradle validation and prepare manual verification checklist
- [x] Add query-scoped Iwara search resolve overlay and rate-limit backoff in `SearchCoordinator`
- [x] Restrict Iwara search cards to static thumbnails and overlay-driven display in `SearchScreen`
- [x] Feed Search-originated resolved posts back into coordinator state from `TheoriaApp`
- [x] Make HTTP retry policy avoid amplifying Iwara `429` responses
- [x] Add regression coverage for overlay/backoff and HTTP retry policy
- [x] Refresh Iwara ExecPlan notes and validate with Gradle
