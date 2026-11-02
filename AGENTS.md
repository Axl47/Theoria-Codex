# THEORIA CODEX AGENTS DOCUMENT

## Development Rules

Keep Detekt's own `parallel` setting disabled: concurrent Kotlin 2.4 FIR/PSI traversal can hang or report missing declarations. Android Debug analysis receives its module's public `JavaCompile.destinationDirectory` after AGP registers variant tasks, so generated BuildConfig and Room Java types resolve. Run lint after multi-variant coverage/code generation finishes; otherwise lint can read Room-generated Java files while another variant regenerates them. Offline lint retains source checks while avoiding dependency-version network lookups. If a reused daemon reports a Kotlin FIR/UAST internal resolver crash after other analysis/build work, finish code generation and retry lint alone with `./gradlew :app:lintDebug --offline --no-daemon`; the fresh-process lane retains every source check and passed the loading-remediation audit without suppressions.

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

## Final Output

If working by yourself, handle creating a commit for each change or fix; if working with an orchestrator/other agents, follow their instructions on commits instead.
Use concise Conventional Commit messages that explain the change; add a body when its purpose or tradeoffs need context. Keep user-facing release notes in `NEXT_CHANGELOG.md`.

## Communication

Explain plans, questions, and completed work in plain system-level language. The user is strongest at holding how the whole app connects and flows, so focus on architecture, feature boundaries, runtime behavior, and user-visible consequences. Keep deep implementation detail available when it matters, but do not lead with it.

Keep `AGENTS.md` limited to durable product intent, safety boundaries, and acceptance or release requirements that cannot be inferred from the codebase. Add findings only when they meet that bar; leave implementation details, app flows, and resolved bug histories in code, tests, or focused documentation.

## Android Support

- Support only the latest stable Android major release and its immediate predecessor; previews do not move the support window. Currently: Android 17 (API 37) and Android 16 (API 36). Keep the minimum SDK, documentation, and test matrix aligned as this window advances.
- Prefer platform APIs available throughout that window and remove obsolete OS compatibility branches when touched. Preserve saved-data and credential migrations for existing users.

## Non-obvious Constraints

- Likes and creator follows are local actions. Do not add remote bookmarks, favorites, follows, or notification subscriptions.
- Every visibly presented animated/video card must autoplay simultaneously. Performance improvements must preserve this behavior.
- Viewer OCR/translation was deliberately retired. Remaining preferences, counters, schemas, and `deploy/libretranslate/` are legacy compatibility artifacts, not unfinished features.

## Personal-device Safety

- The Windows development PC has a hardware-accelerated Android 17 (API 37) emulator available as AVD `Theoria_API_37` (Pixel 6 profile, 4 GB RAM, 6 GB data). Start it with the desktop **Theoria Android Emulator** shortcut or `%LOCALAPPDATA%\TheoriaAndroid\Start-TheoriaEmulator.ps1`; shell setup and local instructions are in the same directory. Discover its current ADB serial before use rather than assuming `emulator-5554`. Use isolated app packages and the checks below for emulator testing; emulator profiling does not satisfy physical-device performance acceptance.
- Protect `com.theoriacodex` and its data. Before any device installation or test, verify every packaged APK’s application ID and signing lane; variant names are insufficient. New or changed device commands require a host-only task-graph/preflight check.
- Automated device lanes must use isolated packages. Production changes require explicit authorization; destructive actions require authorization for the exact action after explaining possible data loss. Production installs use the signed release/update path.
- Developer profile copying must remain read-only against production and excluded from public builds.
- Never alter unrelated phone packages, governors, or persistent device settings to make benchmarks pass. Never configure `SideEffectRunListener`.

## Acceptance and Releases

- Builds, mocks, and host tests do not establish live provider authentication, hardware playback, PiP, or TalkBack behavior. Performance acceptance requires physical-device measurements, simultaneous-autoplay coverage, and retained complete iteration evidence. Calibration requires three complete runs of the same APK under matching conditions.
- Use `.codex/skills/theoria-release/SKILL.md` for release preparation and publishing. Preparing a release does not authorize creating or pushing its tag; publishing requires a separate explicit instruction.

## Next Changelog

After completing a change, add or refine a concise bullet in root `NEXT_CHANGELOG.md` only if app users would notice its effect. Describe the benefit or corrected behavior, following `release-notes/` wording and grouping under `New`, `Improvements`, or `Fixes` as relevant; omit internal refactors, tests, and documentation-only work. Give each distinct user-visible change its own bullet on a separate line, even when one task includes multiple changes. Consolidate only entries describing the same change. The release skill curates these notes and clears published content only after successful publication.
