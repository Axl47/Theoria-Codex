# THEORIA CODEX AGENTS DOCUMENT

## ExecPlans

When writing complex features or significant refactors, use an ExecPlan (as described in `PLANS.md` or the repo root `PLANS.md`) from design to implementation.

## Development Details

Whenever new important updates are made, this file (`AGENTS.md`) should be updated with any surprising files not apparent from the codebase. Additionally, the `README.md` file should be updated with any new features or changes the app receives that are not simple fixes, so that users can easily see what's new without having to read through the codebase.

## Surprising Files Added

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/update/` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/update/GitHubReleaseFeedClientTest.kt`: startup auto-update module (GitHub prerelease feed parsing, download, APK validation, installer launch orchestration, persisted pending state) plus parser contract tests.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/.github/workflows/main-prerelease.yml`: CI release publisher contract for main-channel updater (release title/tag `v<major>.<minor>.<patch>` plus semver-derived Android `versionCode`, with updater backward-compatibility for legacy `main-vc<versionCode>-<sha>` tags and fixed `theoria-codex-main.apk` asset).
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/maintainability-reliability-refactor-execplan.md`: living ExecPlan for persistence integrity, shared media policy, provider contract tests, opt-in provider health, and `TheoriaApp.kt` modularization.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/common/` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/test/kotlin/com/theoriacodex/stubs/StubProviderContractTest.kt`: shared provider helper functions plus fixture-backed provider contract tests that protect search identity, media metadata, tag shape, paging, quick queries, resolve behavior, and typed failures without live network access.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/health/` and the Gradle task `:core-sources:providerHealthCheck`: opt-in live provider health reporting. The task performs no live network work unless run with `-Ptheoria.liveProviders=true`, then writes `core-sources/build/reports/provider-health/provider-health.json`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/CodexShareModels.kt`: app workflow policy extracted from `TheoriaApp.kt` for viewer lazy media/session merging and Codex import/export payload naming/parsing, with focused unit tests under matching `app/src/test` packages.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SourceFailureUiText.kt`: user-facing provider failure message policy for Search status chips and empty-state errors, covering auth, rate limit, network/blocked, parse-change, and unknown failures.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/recents/` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`: Recents tab UI plus persistent `recents_store.json` activity history for watched posts and applied searches; watched posts open Viewer as a static `ViewerStreamSource.RECENTS` list.
- `/Users/axel/Desktop/Code_Projects/Personal/Apps/Theoria Codex/.docs/exec/code-quality-hardening-and-modularization.html`: HTML ExecPlan for the next quality pass: green lint, atomic/tolerant persistence, shared media/download policy, `TheoriaApp.kt` workflow extraction, and test-lane modernization.
- `/Users/axel/Desktop/Code_Projects/Personal/Apps/Theoria Codex/app/src/main/java/com/theoriacodex/app/media/MediaRequestFactory.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Apps/Theoria Codex/app/src/main/java/com/theoriacodex/app/media/PostDownloadService.kt`: shared app media request/download policy for Search, Viewer, and app-level post downloads. Keep source request headers and filename rules here instead of duplicating them in screens.
- `/Users/axel/Desktop/Code_Projects/Personal/Apps/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaAppGraph.kt`: app composition graph for constructing repositories, source clients, update services, credentials, and top-level coordinators outside `TheoriaApp.kt`.
- `/Users/axel/Desktop/Code_Projects/Personal/Apps/Theoria Codex/app/src/androidTest/java/com/theoriacodex/app/TheoriaAppSmokeTest.kt`: device/emulator-backed Compose smoke test for top-level app shell rendering; compile with `:app:compileDebugAndroidTestKotlin` and run with `:app:connectedDebugAndroidTest` when an Android target is attached.

## Final Output

When asking the user to verify implemented changes, output a checklist they can fill to make sure everything works as intended. Describe what they should see, how it should work, and keep in mind the possible checkboxes types (`[x]` is completed, `[~]` is partial, `[ ]` is not completed/not working). The user will then fill in the checklist and provide feedback on any issues they encounter, which can be used to further refine the implementation.

Occasionally, remind the developer of the commands they need to use to test the changes, lest they run the run command and forget to build before then. For example, if they need to run `npm run build` before `npm run start`, remind them of this in the final output instructions.

Include a commit message after each change, following the Conventional Commits specifications. From these commit messages the version changelog gets created, so make the message user-facing.
If it's a big change, follow this format:

```txt
feat(update): add startup update prompt choices and sectioned changelog pipeline

- feat(update): gate startup updates behind user choice (Yes/No/Remind Later)
- feat(update): persist per-release prompt decisions (ignore until newer, 24h remind-later)
- refactor(update): split updater flow into eligibility check and install phases
- feat(update): parse GitHub release body into sectioned changelog blocks for in-app prompt
- test(update): add updater decision/state-store/changelog parser coverage
- feat(ci): generate release notes sections from commit metadata and publish via body_path
- feat(ci): support multi-section changelog from Conventional Commit lines in commit body
- fix(navigation): clamp bottom navbar sizing to prevent tiny rendering on some phones
- fix(navigation): make top-level tab swipe detection more reliable in Explore
- fix(search): move Explore apply+navigate to app scope to prevent canceled loads on slower devices
- docs(readme): document updater prompt behavior and changelog contract
```
