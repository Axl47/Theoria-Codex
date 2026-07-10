# THEORIA CODEX AGENTS DOCUMENT

## ExecPlans

For complex features or significant refactors, use an ExecPlan from design through implementation.

The current plan standard lives at `.docs/PLANS.md`. New ExecPlans should be standalone HTML files in `.docs/exec/<kebab-case-name>.html`. Older Markdown plans in `.docs/exec/execplans/` are historical unless explicitly reactivated.

Keep active ExecPlans current while working: progress, decisions, surprises, validation evidence, and outcomes should reflect reality before the work is closed.

## Subagents

Use the `$solar-orchestration` skill when independent exploration, implementation, testing, or review would materially improve a complex task, or when deciding to use subagents. The primary agent retains responsibility for integration, validation, and the final result. Do not use subagents without first reading the skill.

## Communication

Explain plans, questions, and completed work in plain system-level language. The user is strongest at holding how the whole app connects and flows, so focus on architecture, feature boundaries, runtime behavior, and user-visible consequences. Keep deep implementation detail available when it matters, but do not lead with it.

`AGENTS.md` should be updated whenever an important finding is made to aid new developers in the project. For example, if testing end-to-end behavior requires a non-standard command, add a note to the file. Whatever could speed up further development should be added, but if anything can be acquired from exploring the codebase trust future developers to explore it first.

## Runtime Diagnostics

For installed release-build crashes on a connected Android device, use `adb logcat -b crash -d` even when `run-as com.theoriacodex` is unavailable because the package is not debuggable. Viewer background prefetch must treat provider TLS, socket, and stream failures as unavailable media while rethrowing coroutine cancellation; otherwise an adjacent saved video can terminate the whole app.

## Hitomi And Mixed Media

Hitomi Nozomi indexes are binary streams of big-endian 32-bit gallery IDs. Page them with byte ranges aligned to four-byte records, and treat truncated or misaligned responses as protocol failures rather than decoding them as text.

Hitomi's `gg.js` media configuration is mutable. Recovery is only for an exact media HTTP 404: refresh once for the failed configuration version, then try the alternate shard. Never loop, refresh for unrelated failures, or reinterpret cancellation as provider drift.

Cancellation is a control signal across provider health checks and background transport work. Any boundary that catches broad transport failures must rethrow `CancellationException` before degrading the source or treating media as unavailable.

Animated WebP uses the platform decoder on API 28 and newer. API 26/27 uses the bounded fallback, with compressed input and decoded canvas limits enforced before returning a drawable. Media Overview uses Hitomi's WebP candidate and a distinct static-first-frame request/cache path so grid tiles do not autoplay on any supported API.

Viewer video prefetch requests at most the first 16 MiB and caches only a response proven to be a complete small representation. Partial or larger media stays remote and streams through Media3 with source headers; transport failures remain nonfatal and cancellation must propagate.

## Releases

GitHub prereleases are created only by pushing an annotated `vX.Y.Z` tag. The tagged commit must declare the same `versionName`, its calculated Android `versionCode` (`1_500_000_000 + major * 10_000 + minor * 100 + patch`), and a curated `release-notes/vX.Y.Z.md` file. Do not use a low sequential version code: existing installs and the updater already compare against this high SemVer-derived range.

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
