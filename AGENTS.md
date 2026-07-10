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
