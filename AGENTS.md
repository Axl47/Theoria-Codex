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

Recents separates Viewer history by launch origin: `CODEX` entries appear under Codex, while every other origin appears under Watched. Opening an entry back through Recents must preserve its previous origin so it does not move between those sections merely because it was reopened from history.

For installed release-build crashes on a connected Android device, use `adb logcat -b crash -d` even when `run-as com.theoriacodex` is unavailable because the package is not debuggable. Debug instrumentation installs and exercises the separate `com.theoriacodex.debug` package; use `dumpsys package` and the application flags when evidence must distinguish it from production `com.theoriacodex`. Viewer background prefetch must treat provider TLS, socket, and stream failures as unavailable media while rethrowing coroutine cancellation; otherwise an adjacent saved video can terminate the whole app.

When a saved post is refreshed, update its durable Post and keyed thumbnail cache entry together. Cache replacement must remove every older file representation for that key before writing the new local file or URL pointer, because Codex covers prefer local cache files. An adapter returning `null` from `resolvePost` means the post no longer exists and should become the terminal Viewer message `Post was deleted`; thrown provider or transport failures remain recoverable and must not be collapsed into that missing-post result.

## Hitomi And Mixed Media

Hitomi Nozomi indexes are binary streams of big-endian 32-bit gallery IDs. Page them with byte ranges aligned to four-byte records, and treat truncated or misaligned responses as protocol failures rather than decoding them as text.

Every binary byte-range request must send `Accept-Encoding: identity`. Android's `HttpURLConnection` otherwise transparently inflates a partial gzip stream, which can fail with a premature EOF or make the decoded body disagree with `Content-Range`. Keep this invariant in the shared HTTP byte-range boundary rather than adding Hitomi-only compensation.

Hitomi's `gg.js` media configuration is mutable. Recovery is only for an exact media HTTP 404: refresh once for the failed configuration version, then try the alternate shard. Never loop, refresh for unrelated failures, or reinterpret cancellation as provider drift.

Hitomi protocol objects are initialized while the application graph is built, so their static initialization must run on Android itself. Avoid desktop-JVM-only regular-expression assumptions in startup-loaded parsers: Android ICU rejected an escaped object-brace pattern that JVM tests accepted and crashed before the first screen. Prefer deterministic scanning for the `galleryinfo` assignment. Verify that boundary on a connected device with `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.theoriacodex.app.source.HitomiProtocolDeviceTest`.

Cancellation is a control signal across provider health checks and background transport work. Any boundary that catches broad transport failures must rethrow `CancellationException` before degrading the source or treating media as unavailable.

Animated WebP uses the platform decoder on API 28 and newer. API 26/27 uses the bounded fallback, with compressed input and decoded canvas limits enforced before returning a drawable. Visible Animated WebP tiles in Media Overview must use the animated request path so users can identify a page before selecting it; still-image and video-poster tiles remain static, and off-screen lazy-grid items are not composed.

Hitomi Search cards are intentionally sparse. Opening one from Search must enter the Viewer immediately with the existing preview, then resolve and replace the full gallery through the Viewer's background-resolution path; do not make Search wait for every gallery page. An animated Hitomi preview stays on the WebP path so it can keep playing during that handoff.

The Search `All` scope creates portable plain terms and should not display a facet prefix. A selected facet may expose source-owned featured values before typing; Hitomi uses this for closed Type and Language vocabularies so users can discover valid values without memorizing prefixes.

Hitomi `All` terms are provider-global, not automatically general tags and not autocomplete-classified facets. Mirror Hitomi's versioned `galleriesindex` B-tree for every unqualified term, using the first four SHA-256 bytes as its key and the returned gallery-ID record as the ordered index. Explicit facets continue to use Nozomi.

Main-Viewer animated WebP uses the controllable bounded `awebp` path on every API and exposes honest play/pause, restart, and frame progress controls. The decoder does not support arbitrary seek, so do not present a draggable video-style seek bar. Media Overview Animated tiles autoplay while visible. The Hitomi PNG already contains transparency; do not wrap it in an opaque source-chip background.

Viewer video prefetch requests at most the first 16 MiB and caches only a response proven to be a complete small representation. Partial or larger media stays remote and streams through Media3 with source headers; transport failures remain nonfatal and cancellation must propagate.

## Architecture And Quality Gates

`TheoriaApplication` owns one asynchronously initialized `TheoriaAppContainer`; Compose must not construct repositories, transports, registries, coordinators, or file-backed stores. Search, Viewer, For You, and Creator each use one navigation-scoped ViewModel for immutable state, asynchronous jobs, paging, and typed effects. Cross-route handles are weak, keyed to the owning ViewModel lifetime, and must not become a second state owner.

The maintainability lane runs Detekt, Kover, `jscpd`, architecture source guards, and coverage-helper tests. Detekt uses normal complexity thresholds plus checked historical baselines; do not raise thresholds or regenerate a baseline to hide a new violation. Aggregate line coverage has a 55% floor. The 60% changed-line gate intentionally names JVM/core modules and fails if an eligible source is absent from Kover; Android/Compose runtime behavior is covered by the per-change API 37 device lane, with API 27 and minified release acceptance in the extended scheduled/manual lane. New platform-free app policy should move into a JVM module and be added to the changed-line gate rather than being excluded as Android code.

The launcher identity is the full-color illustrated `theoria_app_icon`. Do not add an adaptive-icon `<monochrome>` layer merely to silence lint: Android uses that layer instead of the custom artwork when themed icons are enabled. A themed replacement requires explicit product/design approval; the missing-monochrome lint signal is intentionally suppressed at the launcher resource boundary.

AGP 9 uses built-in Kotlin for Android modules; do not reapply `org.jetbrains.kotlin.android` or restore the `android.builtInKotlin=false` / `android.newDsl=false` compatibility flags. The Baseline Profile Gradle Plugin must remain on the 1.5 line or newer because 1.4.1 cannot recognize AGP 9's new Android module model.

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
