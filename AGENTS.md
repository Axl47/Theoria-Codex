# THEORIA CODEX AGENTS DOCUMENT

## Development Rules

*Before every PR:* commit your work, then run `scripts/check.sh --record` and fix anything it reports before pushing. It runs the policy checks, unit tests with coverage, and static analysis (Detekt, lint, duplicates), the same checks as GitHub's Verify, and GitHub reuses the result for any commit with the same build inputs (`scripts/release_gate.py input-key`; docs-only commits reuse their parent's). R8, release and benchmark builds (`--part release`) and emulator tests run on GitHub after merge to `main`. `check.sh` also installs `scripts/hooks/pre-push`, which rejects line-budget and changed-file Detekt failures in seconds; bypass it only with `--no-verify`.

Keep Detekt's own `parallel` setting disabled: concurrent Kotlin 2.4 FIR/PSI traversal can hang or report missing declarations. Android Debug analysis receives its module's public `JavaCompile.destinationDirectory` after AGP registers variant tasks, so generated BuildConfig and Room Java types resolve. Run lint after multi-variant coverage/code generation finishes; otherwise lint can read Room-generated Java files while another variant regenerates them. Offline lint retains source checks while avoiding dependency-version network lookups. If a reused daemon reports a Kotlin FIR/UAST internal resolver crash after other analysis/build work, finish code generation and retry lint alone with `./gradlew :app:lintDebug --offline --no-daemon`; the fresh-process lane retains every source check and passed the loading-remediation audit without suppressions.

Leave Kotlin's `-Xbackend-threads` unset. In Kotlin 2.4.21, and still in 2.5.0-RC, parallel code generation adds per-file statistics to a shared list without locking, so compiles fail intermittently with `ArrayIndexOutOfBoundsException` in `JvmIrCodegenFactory.invokeCodegen`. The Kotlin daemon's fallback then recompiles out of process, so the build still passes and the failure is easy to miss. Re-test it after Kotlin upgrades: it made a full compile of `:app`'s main code about a third faster.

Robolectric tests need Java 21 or newer because the Android SDK 36 sandbox refuses older JVMs, failing at class setup with `UnsupportedOperationException`. Set `JAVA_HOME` to a compatible JDK before running them; on the Mac, the default JDK is 19 and Homebrew's works: `JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`.

Gradle also needs a JDK 17 installed for the library modules' `jvmToolchain(17)`, and no toolchain download repository is configured. Gradle finds JDKs only in standard locations, so a JDK installed by mise or another version manager must be listed in `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`, or the check fails with "Cannot find a Java installation ... languageVersion=17".

Screenshot reference images (`app/src/test/screenshots`) are CI's Linux renders; macOS draws text differently, so a Mac can only capture screens to look at (`scripts/screenshots.sh capture`) and never records or compares references. After a UI change fails Verify's screenshots part, `scripts/screenshots.sh record` pulls that run's Linux renders; commit them. Screenshots use fixture posts with solid-color images, never provider media.

Fresh git worktrees lack the gitignored `local.properties`, so Android Gradle tasks fail with "SDK location not found"; copy it from the main checkout or set `ANDROID_HOME` first.

On a sandboxed workstation, a Gradle daemon in another sandbox can hold `~/.gradle`'s file-content cache lock where it cannot be stopped, so builds fail with "Timeout waiting to lock file content cache". Give the checkout its own `GRADLE_USER_HOME` (copying `caches/modules-2` and `wrapper/dists` avoids a full download) and copy `gradle.properties` into it, or the JDK 17 toolchain is not found.

Close any T3 `agent-device` session before `am instrument`: its accessibility helper holds UiAutomation, so Compose journey tests fail with "UiAutomationService already registered" or "UiAutomation not connected".

*Smallest sufficient implementation:* Prefer the simplest design that satisfies the stated requirements. Do not expand architecture or scope without a concrete requirement.

*First-pass convergence:* Before the initial patch, identify the required data flow, UI states, error paths, acceptance checks, and validation plan. Prefer one coherent implementation pass over speculative partial patches.

*Bounded validation:* Plan one focused validation batch. Avoid repeated snapshots, equivalent selector checks, duplicate browser setup, and full revalidation unless a later patch changed the relevant behavior.

*Report retention:* Regularly prune generated reports, performance traces, recordings, and duplicate captures under `build/reports/` once the related work is complete and the evidence is no longer needed. Keep complete iteration evidence required for active acceptance or release decisions; archive anything that must be retained before pruning.

*Offline preflight:* Before a substantial offline Gradle batch, check that its required dependencies and generated artifacts are available; resolve missing prerequisites before running the batch.

*Root-cause repair:* When validation fails, identify the common cause and group related fixes into one patch instead of repairing symptoms one at a time.

*Stop after sufficient evidence:* Once the required validation passes, stop unless there is a reproducible defect, missing requirement, or explicit evidence gap. 

*Maintainability:* Long term maintainability is a core priority. If you add new functionality, first check if there is shared logic that can be extracted to a separate module. Duplicate logic across multiple files is a code smell and should be avoided. Don't be afraid to change existing code. Don't take shortcuts by just adding local logic to solve a problem.

*Cross-source parity:* Performance, preloading, network and smoothness optimizations live in shared code (media delivery, Viewer prefetch, HTTP client, player pool) and apply by default to every source that can supply what they need, Custom Sites included. Decide from what a post's media offers, not from lists of source names; a source that must opt out says why where it opts out. When an optimization needs work in one source (a smaller rendition, an early video URL), check every other source in the same change and either wire it up or record why it cannot benefit.

## ExecPlans

For complex features or significant refactors, use an ExecPlan from design through implementation.

The current plan standard lives at `.docs/PLANS.md`. New ExecPlans should be standalone HTML files in `.docs/exec/<kebab-case-name>.html`. Plans are local history: `.docs/exec/` is gitignored, so never commit a plan or link one from committed files. From a worktree, write the plan to the main checkout's `.docs/exec/` (`$(git rev-parse --git-common-dir)/../.docs/exec/`), because worktrees are deleted after landing.

Keep active ExecPlans current while working: progress, decisions, surprises, validation evidence, and outcomes should reflect reality before the work is closed.

## Final Output

If working by yourself, handle creating a commit for each change or fix; if working with an orchestrator/other agents, follow their instructions on commits instead.
Use concise Conventional Commit messages that explain the change; add a body when its purpose or tradeoffs need context.

## Communication

Explain plans, questions, and completed work in plain system-level language. The user is strongest at holding how the whole app connects and flows, so focus on architecture, feature boundaries, runtime behavior, and user-visible consequences. Keep deep implementation detail available when it matters, but do not lead with it.

Keep `AGENTS.md` limited to durable product intent, safety boundaries, and acceptance or release requirements that cannot be inferred from the codebase. Add findings only when they meet that bar; leave implementation details, app flows, and resolved bug histories in code, tests, or focused documentation.

## Android Support

- Support only the latest stable Android major release and its immediate predecessor; previews do not move the support window. Currently: Android 17 (API 37) and Android 16 (API 36). Keep the minimum SDK, documentation, and test matrix aligned as this window advances.
- Prefer platform APIs available throughout that window and remove obsolete OS compatibility branches when touched. Saved-data and credential migrations may be removed once more than two releases have shipped after the last release that needed them. The app has two users, both updating daily.

## Non-obvious Constraints

- Likes and creator follows are local actions. Do not add remote bookmarks, favorites, follows, or notification subscriptions.
- Every visibly presented animated/video card must autoplay simultaneously. Performance improvements must preserve this behavior.
- Viewer OCR/translation was deliberately retired. Remaining preferences, counters, schemas, and `deploy/libretranslate/` are legacy compatibility artifacts, not unfinished features.

## Personal-device Safety

- The Android emulator on the Windows development PC is not available for now, so do not plan device or emulator testing around it.
- On the Mac, run device tests on the `theoria-search-test` AVD (Pixel 6, API 36, `google_apis` arm64-v8a, no Play Store), opened through T3 Code's `device_open`. Recreate it with those settings if it is missing.
- On the Linux workstation, run device tests on the `agent-1` to `agent-3` AVDs (Pixel 8, API 36, `google_apis` x86_64, no Play Store, KVM-accelerated), opened through T3 Code's `device_open`.
- Inside a ward, the `agent-device` command that `device_open` returns is not visible (the ward hides T3's user data), so the app's UI cannot be driven by hand there. Drive it from an instrumented test (`am instrument`) and read its text output.
- These AVD images have no `curl`; to fetch from a host or `adb reverse` port on the device, use `toybox nc` and keep its input open about 2 s, since it hangs up as soon as its input ends.
- Other worktrees share these AVDs, so its `com.theoriacodex.debug` data can hold a newer Room schema and fail at launch with "Saved data could not be opened safely". Test on the isolated `releaseAcceptance` APK (`com.theoriacodex.acceptance`) instead of clearing debug data.
- Debug builds are signed with each machine's own `~/.android/debug.keystore`, and the personal phone's `com.theoriacodex.debug` carries the Mac's key, so a debug APK built elsewhere fails to install there with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Build debug APKs for the phone on the Mac (delegate it from other machines) and confirm with `apksigner verify --print-certs` that the certificate matches the installed app's; never uninstall to get past the mismatch, since that wipes its data.
- `scripts/verify_debug_device.sh` refuses to run while the personal phone is also connected, and that connection is not ours to change. Instead, run `scripts/verify_device_apk.py` on both APKs, confirm the target's `ro.kernel.qemu` is `1`, then use `adb -s <emulator-serial>` to `install -r -t` both and run `shell am instrument -w -e class <test> com.theoriacodex.debug.test/androidx.test.runner.AndroidJUnitRunner`.
- Protect `com.theoriacodex` and its data. Before any device installation or test, verify every packaged APK’s application ID and signing lane; variant names are insufficient. New or changed device commands require a host-only task-graph/preflight check.
- Automated device lanes must use isolated packages. Production changes require explicit authorization; destructive actions require authorization for the exact action after explaining possible data loss. Production installs use the signed release/update path.
- Developer profile copying must remain read-only against production and excluded from public builds.
- Never alter unrelated phone packages, governors, or persistent device settings to make benchmarks pass. Never configure `SideEffectRunListener`.
- Exception: for unattended benchmark or test runs, keep the screen awake (screen timeout or `svc power stayon`) and restore the original value when the run ends.
- Provider feeds are poorly moderated and can show sexualized depictions of minors. When testing on a device, use logs and accessibility text instead of screenshots or recordings wherever feeds, search results, or the Viewer show provider media. Do not save, describe, or look at such content; if it appears, leave that screen and tell the user.

## Acceptance and Releases

- Builds, mocks, and host tests do not establish live provider authentication, hardware playback, PiP, or TalkBack behavior. Performance acceptance requires physical-device measurements, simultaneous-autoplay coverage, and retained complete iteration evidence. Calibration requires three complete runs of the same APK under matching conditions.
- Use `.codex/skills/theoria-release/SKILL.md` for releases. Approving the version and release notes authorizes the agent to commit the release files straight to `main`, run the Release workflow, and publish.
- Results are recorded per code, not per commit: the pre-PR check (`scripts/check.sh --record`, or Verify on the PR), the screenshot comparison (Verify only), the R8 release part, and the emulator tests on Android 16 and 17. PRs run only the pre-PR check and the screenshot comparison, skip each one already recorded, and only read caches.
- Every push to `main` runs all four in the Main workflow, even when the pre-PR check is recorded, and is the only run that saves caches. Release only promotes: it requires all four results for the commit, runs any missing one itself rather than failing, and creates the tag only after everything passes.
- `models-<family>-N` prereleases (such as `models-vision-1`) hold files that shipped APKs download on demand, pinned by SHA-256. Never edit, replace or delete one; publish a new N instead. Model tags never start with `v`, so they can't be mistaken for app releases.
- Emulator tests do not run on PRs. For a risky change, such as a database migration, navigation, or playback, also run `gh workflow run device-validation.yml --ref <branch>`; branch runs never record a result.
- TODO: on CI, an emulator occasionally dies within a second of its tests finishing, all of them passed, and the workflow quietly repeats that run on a fresh emulator. The cause is still unknown: either swangle (SwiftShader Vulkan) crashes while freeing the finished app's graphics, or the runner kills `qemu` for memory. The next time a run shows an "Emulator lost" warning, open the `host-after.txt` it names in that job's report artifact; every run keeps one as a baseline. A kernel segfault line naming a SwiftShader library points at the GPU mode; an out-of-memory kill of `qemu` points at stopping the build step's Gradle and Kotlin daemons before the emulator boots. Fix the cause, then delete this note.

Landing: PR, then green checks, then merge; update local `main` and delete the branch and worktree.
