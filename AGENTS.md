# THEORIA CODEX AGENTS DOCUMENT

## Development Rules

*Smallest sufficient implementation:* Prefer the simplest design that satisfies the stated requirements. Do not expand architecture or scope without a concrete requirement.

*First-pass convergence:* Before the initial patch, identify the required data flow, UI states, error paths, acceptance checks, and validation plan. Prefer one coherent implementation pass over speculative partial patches.

*Bounded validation:* Plan one focused validation batch. Avoid repeated snapshots, equivalent selector checks, duplicate browser setup, and full revalidation unless a later patch changed the relevant behavior.

*Root-cause repair:* When validation fails, identify the common cause and group related fixes into one patch instead of repairing symptoms one at a time.

*Stop after sufficient evidence:* Once the required validation passes, stop unless there is a reproducible defect, missing requirement, or explicit evidence gap. 

*Maintainability:* Long term maintainability is a core priority. If you add new functionality, first check if there is shared logic that can be extracted to a separate module. Duplicate logic across multiple files is a code smell and should be avoided. Don't be afraid to change existing code. Don't take shortcuts by just adding local logic to solve a problem.

## ExecPlans

For complex features or significant refactors, use an ExecPlan from design through implementation.

The current plan standard lives at `.docs/PLANS.md`. New ExecPlans should be standalone HTML files in `.docs/exec/<kebab-case-name>.html`. Older Markdown plans in `.docs/exec/execplans/` are historical unless explicitly reactivated.

Keep active ExecPlans current while working: progress, decisions, surprises, validation evidence, and outcomes should reflect reality before the work is closed.

## Communication

Explain plans, questions, and completed work in plain system-level language. The user is strongest at holding how the whole app connects and flows, so focus on architecture, feature boundaries, runtime behavior, and user-visible consequences. Keep deep implementation detail available when it matters, but do not lead with it.

`AGENTS.md` should be updated whenever an important finding is made to aid new developers in the project. For example, if testing end-to-end behavior requires a non-standard command, add a note to the file. Whatever could speed up further development should be added, but if anything can be acquired from exploring the codebase trust future developers to explore it first.

## Provider Pagination

For page-number providers, derive continuation from authoritative provider metadata when available, otherwise from the number of raw provider records received. Never derive it from the number of records that successfully became `Post` objects: malformed records may be omitted from the visible page without falsely marking that source exhausted in Unified search.

Provider pages must publish unique canonical `Post.id` values after hydration. Raw provider IDs can repeat, and distinct raw IDs can resolve to the same canonical post identity; deduplicate only after hydration while continuing to advance pagination from the raw provider records.

## Search Scroll Restoration

The Search route applies persisted scroll position once when the route is restored or re-entered. Page appends must not retrigger that restoration from a changed result count, or pagination will replay the initial saved position and jump the grid to the top. Keep route-entry restoration separate from page-loading state. Unified execution may retain `EXCLUDED` source statuses for orchestration diagnostics, but the UI status row should only render actionable provider failures.

## Feed Autoplay Performance

Search, For You, Creator Profile, Recents, and Codex browsing must keep every visibly presented video or animated card autoplaying simultaneously. Performance work may share request, cache, media-source, buffering, and decode infrastructure; keep players stable across recomposition; and pause or release cards only after they are no longer visibly presented or the app lifecycle stops. Do not replace concurrent visible autoplay with a single-active-card policy. Validate this contract with multi-card behavior coverage and numeric frame/network/memory evidence rather than assuming fewer players is acceptable.

## Settings Sections

Settings cards use the shared `SettingsSection` composable with independently persisted expanded state owned by `TheoriaAppContent` and stored through `UiRestoreRepository`, so leaving and reopening the app does not reset the user's choices. New settings groups should use the same header and right-side chevron pattern rather than introducing another section-specific collapse control.

## Releases

GitHub prereleases are created only by pushing an annotated `vX.Y.Z` tag. The tagged commit must declare the same `versionName`, its calculated Android `versionCode` (`1_500_000_000 + major * 10_000 + minor * 100 + patch`), and a curated `release-notes/vX.Y.Z.md` file. Do not use a low sequential version code: existing installs and the updater already compare against this high SemVer-derived range.

`actions/checkout` can replace its local tag ref with the peeled commit during a tag-push workflow. The prerelease workflow must explicitly refetch `refs/tags/$GITHUB_REF_NAME` before proving it is annotated and that its annotation matches the checked-in release notes; otherwise a valid annotated tag can fail the release gate.

Use the repo-local `$theoria-release` skill in `.codex/skills/theoria-release/` whenever preparing or publishing a release. It drafts notes before making changes and requires a separate explicit publish instruction before it creates or pushes a tag.

## Final Output

Include a Conventional Commit message after each change. These commit messages feed the version changelog, so make the message user-facing.

For a larger change, use this style:

```txt
feat(recents): add durable watched and search history

- feat(recents): add watched/search activity history
- feat(recents): reopen watched posts as a static Viewer stream
- feat(search): record applied queries through SearchCoordinator
- feat(codex): add JSON import/export for saved collections
- fix(viewer): preserve lazy media resolution across multi-page posts
- docs(readme): document current navigation and persistence model
```
