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

## Pixiv Authorization

Pixiv's browser-visible HTTPS authorization callback carries OAuth `state`, but its final native handoff to `pixiv://account/login` can omit that parameter. Accept a missing state only for that exact provider-native callback; the app-owned `theoriacodex://pixiv-auth/callback` remains strict, and either callback must reject a nonblank conflicting state. Keep PKCE verifier binding, the short session lifetime, encrypted durable session storage, and one-shot consumption intact. A compiled or stubbed callback test is not authenticated live-provider proof.

## Provider Pagination

For page-number providers, derive continuation from authoritative provider metadata when available, otherwise from the number of raw provider records received. Never derive it from the number of records that successfully became `Post` objects: malformed records may be omitted from the visible page without falsely marking that source exhausted in Unified search.

Provider pages must publish unique canonical `Post.id` values after hydration. Raw provider IDs can repeat, and distinct raw IDs can resolve to the same canonical post identity; deduplicate only after hydration while continuing to advance pagination from the raw provider records.

## Search Scroll Restoration

The Search route applies persisted scroll position once when the route is restored or re-entered. Page appends must not retrigger that restoration from a changed result count, or pagination will replay the initial saved position and jump the grid to the top. Keep route-entry restoration separate from page-loading state. Unified execution may retain `EXCLUDED` source statuses for orchestration diagnostics, but the UI status row should only render actionable provider failures.

`UiRestoreRepository` is the sole live Search scroll store. `query_store.json` owns applied queries only; its pre-F05 `scrollOffsets` field is a one-time DataStore migration input and is removed after a verified import. SearchViewModel owns debounce and registers a closeable scheduler that synchronously waits for its final DataStore write during ViewModel teardown before cancelling that scheduler. This deliberately trades a storage-operation-length teardown stall for a provable final flush; do not move that flush into the already-cancelled `viewModelScope` or add a lossy timeout.

## Browsing Destination Readiness

`BrowsingDestinationStateBoundary` must not mount Search or For You with synthetic `AppSettings()` or empty-like placeholders while repository flows are still loading. Wait for the first authoritative settings, liked-ID, and active-profile likes emissions. For You retains its current feed in its navigation-scoped owner/coordinator; a transient zero-like placeholder clears that cache and regenerates the same recommendation when real data arrives.

## Recents Section Identity

Watched and Codex are independent Recents memberships, not mutually exclusive labels derived from the latest Viewer origin. One canonical post may have one row in each section while sharing the same `posts` payload. Keep section in the `recent_watched` identity, preserve exact launch origin as row metadata, carry the section explicitly through `ViewerLaunchContext` when reopening from Recents, and clear or route by section. The combined All activity view may collapse duplicate canonical posts to the newest membership, but the filtered sections must retain both.

## Legacy JSON Recovery

`AtomicJsonFileStore` owns verified quarantine for the remaining live whole-file JSON stores: applied queries, Recents, updater state, and tag suggestions. A present malformed, empty, null, or invalid UTF-8 file is never a normal miss: preserve and verify its exact bytes under the deterministic filename/byte-count/SHA-256 identity before removing the live name or returning a default. Register each production owner with the shared `LegacyJsonRecoveryRegistry` so verified quarantines are rediscovered across process restart and surfaced through Settings. Do not apply this fallback to DataStore newer-schema failures or Room migration conflicts; those contracts remain fail-closed.

## Feed Autoplay Performance

Search, For You, Creator Profile, Recents, and Codex browsing must keep every visibly presented video or animated card autoplaying simultaneously. Performance work may share request, cache, media-source, buffering, and decode infrastructure; keep players stable across recomposition; and pause or release cards only after they are no longer visibly presented or the app lifecycle stops. Do not replace concurrent visible autoplay with a single-active-card policy. Validate this contract with multi-card behavior coverage and numeric frame/network/memory evidence rather than assuming fewer players is acceptable.

Animated-duration enrichment is application-owned work shared by Search, For You, and Creator Profile. Composables emit typed requests only; their route owners drain bounded batches for the current query/seed identity and apply immutable duration-only updates. Keep cross-route single-flight, bounded positive and negative caches, cancellation isolation, and stale-identity rejection in the shared enrichment path rather than reintroducing per-screen resolve/probe loops.

## Platform-Free Application Logic

`app-logic` is the Kotlin/JVM owner for Search state/reducers, visibility filtering, feed activation/decode policy, media classification/duration policy, recommendation tag policy, and animated-duration candidate/drain scheduling. Keep Android routes, ViewModels, provider services, Media3/Coil, lifecycle, and source-specific URL/header normalization in `app`. The module may depend on `core-domain`, `core-data`, and coroutines only; it is explicitly covered by Detekt, aggregate Kover, and the 60% changed-line gate. The canonical Pixiv Ugoira wire MIME lives in `core-domain`; provider code retains only a compatibility alias.

All five browsing feeds converge on `SearchResultCard` and `FeedMediaComponents`. A video card must intersect the clipped window and reach a started lifecycle before its first player is constructed/prepared; while the card remains composed, later offscreen or lifecycle stops pause that same player and do not prepare another. Media3's HTTP/local factories and 64 MiB byte-evicted cache are application-owned, but protected headers remain request-scoped and each ExoPlayer receives a fresh load-control instance from the shared bounded policy. Do not turn the player-activation latch into eager offscreen preparation or share a state-owning `DefaultLoadControl` across concurrent players.

Numeric startup, Search concurrent-autoplay, and Viewer-swipe measurements live in the separate `macrobenchmark` module and target `benchmarkRelease`. Run `:macrobenchmark:connectedBenchmarkReleaseAndroidTest` only on physical hardware; build-only or emulator results are not performance proof. The offline fixture activity exists only in `app/src/benchmarkRelease`, runs in `:benchmarkFixture`, skips production container startup in that exact compile-time-enabled process, and publishes benchmark-only playback diagnostics. Preserve the complete `macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<device>/` directory because it contains the JSON result and one Perfetto trace per iteration.

Never configure `androidx.benchmark.junit4.SideEffectRunListener` as a runner argument in the personal-device benchmark lane. In AndroidX Benchmark 1.5.0-alpha07 it disables 41 unrelated packages, including Play Store and Google Play services, then unconditionally enables every package without restoring prior state. The required benchmark library may still package the listener and `DisablePackages` classes; class presence is harmless because no runner argument instantiates them. Source guards and packaged-runner manifest verification prove the listener is unconfigured. The benchmark app APK separately isolates its application ID and storage and removes production deep links, App Links verification, install/network permission, and FileProvider.

## Production App Data Safety

Treat the production package `com.theoriacodex` and its private data as protected user data. Before running any Gradle task, Android Studio action, script, ADB command, connected test, baseline-profile collection, or benchmark that can install, uninstall, replace, clear, downgrade, or launch an APK, prove the packaged application ID and signing lane first. Build-only assembly tasks do not touch a device, but do not infer install safety from a task or variant name.

Every debug-signed or device-testable application variant must use a non-production application ID: Debug uses `com.theoriacodex.debug`, macrobenchmark uses `com.theoriacodex.benchmark`, release acceptance uses `com.theoriacodex.acceptance`, and baseline-profile collection uses `com.theoriacodex.baselineprofile`. Keep automated source and packaged-artifact guards for these identities. Never run a connected lane on a personal device if its target resolves to `com.theoriacodex`, and never use `adb uninstall`, `pm clear`, signature-mismatch uninstall/reinstall, or an install flag that removes production data unless the user explicitly authorizes that exact destructive production-package action after being warned that saved data can be lost.

For a new or changed device command, use a host-only dry run to inspect its task graph, verify output metadata or the packaged manifest for every APK it can install, and confirm that no production-ID target or package-mutating listener is configured. If that proof is incomplete, stop before connecting to the device. Use `installDebug` only for the isolated Debug app; production releases must be installed only through the signed release/update path.

## Settings Sections

Settings cards use the shared `SettingsSection` composable with independently persisted expanded state owned by `TheoriaAppContent` and stored through `UiRestoreRepository`, so leaving and reopening the app does not reset the user's choices. New settings groups should use the same header and right-side chevron pattern rather than introducing another section-specific collapse control.

First-open Settings sections default collapsed only when their persistence key is absent; explicit stored true or false choices remain authoritative. Collapsed summaries expose compact repeated-use state only. Keep credentials, per-source weights, cache-clearing actions, and other sensitive or destructive controls inside expanded content.

## Codex Collection Actions

`CodexListScreen` owns one collection-action sheet reached by both the compact tile overflow affordance and tile long-press; keep export/share, search, rename, and delete behavior in that shared surface rather than creating divergent entry-point logic. `CodexDetailScreen` owns explicit multi-post edit selection through `CodexEditSelection`, while long-press retains the full single-post action sheet. Do not add permanent overflow controls to individual feed or Codex post cards to expose these actions.

## Actionable Transient Feedback

`TheoriaAppContent` owns the app's single `SnackbarHost`, positioned by the outer Scaffold above bottom navigation. Feature routes send typed requests to that shell boundary; keep passive confirmations on Toast and reserve snackbars for immediate Undo or Retry actions. Recents clear Undo restores exact repository snapshots so timestamps, provenance, search identity, and Watched/Codex section membership survive. Codex detail removal uses the same boundary and restores exact saved timestamps/payloads for single or bulk selection. For You seed mutations run independently from settings-triggered route refresh cancellation; Undo carries the originating profile and only the blacklist entries newly added by that hide action, so it cannot remove pre-existing exclusions.

Android 13 and newer render their own clipboard confirmation. All clipboard writes go through the shared clipboard helper, which shows feature-specific success Toasts only through Android 12L and relies on the single system confirmation on newer releases. Failure feedback remains app-owned on every version; do not add unconditional copy-success Toasts at call sites.

## Releases

GitHub prereleases are created only by pushing an annotated `vX.Y.Z` tag. The tagged commit must declare the same `versionName`, its calculated Android `versionCode` (`1_500_000_000 + major * 10_000 + minor * 100 + patch`), and a curated `release-notes/vX.Y.Z.md` file. Do not use a low sequential version code: existing installs and the updater already compare against this high SemVer-derived range.

`actions/checkout` can replace its local tag ref with the peeled commit during a tag-push workflow. The prerelease workflow must explicitly refetch `refs/tags/$GITHUB_REF_NAME` before proving it is annotated and that its annotation matches the checked-in release notes; otherwise a valid annotated tag can fail the release gate.

Use the repo-local `$theoria-release` skill in `.codex/skills/theoria-release/` whenever preparing or publishing a release. It drafts notes before making changes and requires a separate explicit publish instruction before it creates or pushes a tag.

Release JSON verification must run through `:app:verifyReleaseJsonContracts` and `:app:verifyReleaseAcceptanceJsonContracts`. Those tasks consume AGP's public `SingleArtifact.OBFUSCATION_MAPPING_FILE`; AGP 9.1.1 exposes no public seeds artifact, so the matching `outputs/mapping/<variant>/seeds.txt` remains a separate explicit task input. Do not invoke the Python verifier against a guessed intermediates path or infer seeds beside the intermediate mapping.

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
