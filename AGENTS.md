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

## Grouped Search Tags

Positive Search terms use a shallow Boolean grammar: every group is required with AND, while terms inside one group are alternatives with OR. Exclusions remain flat. Group matching is eligibility only and must never boost or otherwise replace the selected provider/Unified ordering. Persist group boundaries in query hashes, applied queries, Saved State, and Recents; legacy flat includes decode as singleton required groups.

Gelbooru supports exact native OR with brace groups such as `{tag1 ~ tag2}`; its older bare-tilde form is not equivalent. Providers without verified native support use the bounded orchestrator fallback with independent branch continuation, post-hydration group verification, and canonical-ID deduplication. Never infer a fallback branch's exhaustion from the locally filtered visible count.

## Search Scroll Restoration

The Search route applies persisted scroll position once when the route is restored or re-entered. Page appends must not retrigger that restoration from a changed result count, or pagination will replay the initial saved position and jump the grid to the top. Keep route-entry restoration separate from page-loading state. Unified execution may retain `EXCLUDED` source statuses for orchestration diagnostics, but the UI status row should only render actionable provider failures.

`UiRestoreRepository` is the sole live Search scroll store. `query_store.json` owns applied queries only; its pre-F05 `scrollOffsets` field is a one-time DataStore migration input and is removed after a verified import. SearchViewModel owns debounce and registers a closeable scheduler that synchronously waits for its final DataStore write during ViewModel teardown before cancelling that scheduler. This deliberately trades a storage-operation-length teardown stall for a provable final flush; do not move that flush into the already-cancelled `viewModelScope` or add a lossy timeout.

The collapsed Search field renders applied context through its existing unfocused placeholder slot. Build that summary only from `applied` query/source state plus current visibility-filter state; never copy the summary into the real text input or present draft terms as applied. Keep it one line with ellipsis and do not add a separate applied-query row.

## Recents Section Identity

Watched and Codex are independent Recents memberships, not mutually exclusive labels derived from the latest Viewer origin. One canonical post may have one row in each section while sharing the same `posts` payload. Keep section in the `recent_watched` identity, preserve exact launch origin as row metadata, carry the section explicitly through `ViewerLaunchContext` when reopening from Recents, and clear or route by section. The combined All activity view may collapse duplicate canonical posts to the newest membership, but the filtered sections must retain both.

FYP Recents stores recommendation searches, never the posts returned by those searches. `ForYouCoordinator` records one FYP search only after an accepted root generation, using its exact tags per source, participating-source order, sort, and seed identity; pagination, stale/cancelled/failed work, recomposition, and Viewer activity must not create FYP search entries. Reopening dispatches a typed replay through the navigation-owned For You route before moving tabs, and that replay supersedes any automatic refresh so the saved per-source query format wins. On a cold route owner, buffer replay until both the first authoritative environment synchronization and the first source-availability reconciliation complete; either startup pass can otherwise clear or cancel the historical request. An explicit replay seed also takes precedence over the no-likes training empty state. Keep FYP independently clearable with exact Undo and include it in All, while direct For You post viewing remains normal Watched history. The legacy post-section enum value remains decodeable for compatibility, but those obsolete rows stay hidden from combined activity.

Recent Searches preserve the accepted execution kind and participating sources. Temporary multi-source executions are recorded and reopen as Multi-Search with their explicit source set, but they remain excluded from durable applied-query and Search-scroll restoration. Room stores this metadata in the versioned recent-search wrapper inside the existing query payload column; keep decoding the legacy raw Query payload so existing history remains readable without a database migration.

Watched Recents retains one-based multi-media progress as the highest media number ever made visible for that post and section. Viewer post visibility remains the one-shot owner of lifetime watched statistics; later media-page changes update only the monotonic Recents progress and must not reorder history or rewrite the shared post payload. Existing rows start at media 1, and only the Watched card badge presents `highest/total`; Codex and other card surfaces keep the total-only badge.

## Feed Autoplay Performance

Search, For You, Creator Profile, Recents, and Codex browsing must keep every visibly presented video or animated card autoplaying simultaneously. Performance work may share request, cache, media-source, buffering, and decode infrastructure; keep players stable across recomposition; and pause or release cards only after they are no longer visibly presented or the app lifecycle stops. Do not replace concurrent visible autoplay with a single-active-card policy. Validate this contract with multi-card behavior coverage and numeric frame/network/memory evidence rather than assuming fewer players is acceptable.

Animated-duration enrichment is application-owned work shared by every browsing route. Composables emit typed viewport, filter, lifecycle, and scroll-idle events only. Each navigation-scoped `MediaDurationRouteViewModel` reconciles per-media demand deltas for its current content identity and exposes only that route's metadata subset; it never rewrites the route's `Post` list. `MediaDurationCoordinator` remains the sole acquisition/scheduling owner with cross-route single-flight, bounded durable positive/negative decisions, cancellation isolation, and stale-identity rejection. Keep media keys cached per result snapshot and publish player durations only from one-shot authoritative full-media callbacks; do not reintroduce result-list resolve/probe effects or progress-cadence publication.

Duration viewport callbacks are a per-card hot path. Precompute animated post keys and candidates once per changed feed snapshot; an enter/exit event may reconcile only that post's precomputed key and must not rebuild, hash, or scan the full feed. Reusing a cached Known decision must schedule no demand, and a reconstructed player must not republish or replace existing Known metadata. Provider/probe acquisition at every priority pauses while the feed is actively scrolling; already-active visible players continue autoplaying and may opportunistically publish a previously unknown duration.

Durable duration decisions live independently from posts and Codex snapshots in Room's bounded `media_durations` table. Key them by canonical post identity plus the opaque authoritative-media fingerprint; persist Known and Unsupported decisions and only unexpired Retryable Failure decisions. Never store media URLs, request headers, full posts, or Pending process state in this table. A fingerprint change must miss naturally rather than mutating an older row into authority for different media.

Remote duration fallback is a strict transport operation, not media playback: use request-scoped source headers and at most 256 KiB head plus 256 KiB tail ranges, validate partial-content boundaries, apply the 12-second overall timeout, and parse MP4/WebM metadata through the pure bounded parsers. Production remote acquisition must not use `MediaMetadataRetriever` or a hidden ExoPlayer; the retriever remains permitted only in the isolated benchmark baseline workload or truly local-only media paths.

When unknown-duration resolution is enabled, browsing routes request enrichment as soon as unknown animated posts arrive so card metadata and later duration filtering share the same acquired values; do not gate acquisition on opening or changing the duration filter. A remote duration probe may measure only authoritative full media from a resolved/provider payload. Never probe preview-only autoplay clips, because their loop length is not the post duration. Duration slider handles are literal thresholds: ordinary upper handles are inclusive, while the endpoint buckets retain their under-5-second and over-2-minute meanings.

Sparse provider cards must retain authoritative animation identity and directly known full video media even when image galleries remain deferred; otherwise feed classification and duration acquisition incorrectly depend on a Viewer round trip. An active duration range contains animated posts with matching known durations only: static and unresolved records stay out. GIF Viewer loading tries every local, progressive, and canonical candidate, retries transient failures once, logs a source/host-safe failure reason, and falls back to Coil's animated decoder when the seekable Movie decoder rejects valid media.

## Platform-Free Application Logic

`app-logic` is the Kotlin/JVM owner for Search state/reducers, visibility filtering, feed activation/decode policy, media classification/duration policy, recommendation tag policy, and animated-duration candidate/drain scheduling. Keep Android routes, ViewModels, provider services, Media3/Coil, lifecycle, and source-specific URL/header normalization in `app`. The module may depend on `core-domain`, `core-data`, and coroutines only; it is explicitly covered by Detekt, aggregate Kover, and the 60% changed-line gate. The canonical Pixiv Ugoira wire MIME lives in `core-domain`; provider code retains only a compatibility alias.

All five browsing feeds converge on `SearchResultCard` and `FeedMediaComponents`. A video card must intersect the clipped window continuously for the short stable-visibility delay and reach a started lifecycle before it leases and prepares a player; a fast fling must leave transient cards on their lightweight image previews. Cards that leave the visible window relinquish their lease even if Lazy layout keeps their composition alive. Media3's HTTP/local factories and 256 MiB byte-evicted cache are application-owned, but protected headers remain request-scoped and each ExoPlayer receives a fresh load-control instance from the shared bounded policy. Do not bypass the stable-visibility gate with eager offscreen preparation or share a state-owning `DefaultLoadControl` across concurrent players. Application-owned feed player objects may remain reusable after a card returns its lease, but only two idle media bindings stay warm after the grace period; cool excess idle bindings with `stop()` and `clearMediaItems()`, and clear the old binding before reusing a slot for another media identity. This warm-idle bound must never cap distinct concurrently visible leases.

Feed preview ExoPlayers are leased from the application-owned reusable slot pool, and lazy-grid `PlayerView`s opt into `AndroidView` reuse. Losing visibility, forgetting a card, or leaving a route must detach its surface, remove the card listener, pause, and return the lease without synchronously calling `ExoPlayer.release()`; slow release is delayed and paced only after no preview lease is active. Rebinding a returned player to another post must first stop and clear the prior media so decoder and allocator lifetimes cannot overlap. Visible leases enter one cancellation-aware prepare queue paced at short intervals, preventing a fling from starting every new hardware decoder in one frame without limiting how many visible cards may play. The idle-retention bound must never cap simultaneous visible autoplay. Muted feed players disable the audio track type so they do not allocate audio decoders merely to render silent previews. Keep player creation, prepare, rebind, cooling, release, active-count, and total-count trace instrumentation when changing this lifetime boundary so long-scroll and tab-switch acceptance can distinguish leasing from actual codec teardown.

Numeric startup, Search concurrent-autoplay, and Viewer-swipe measurements live in the separate `macrobenchmark` module and target `benchmarkRelease`. Run `:macrobenchmark:connectedBenchmarkReleaseAndroidTest` only on physical hardware; build-only or emulator results are not performance proof. The offline fixture activity exists only in `app/src/benchmarkRelease`, runs in `:benchmarkFixture`, skips production container startup in that exact compile-time-enabled process, and publishes benchmark-only playback diagnostics. Preserve the complete `macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<device>/` directory because it contains the JSON result and one Perfetto trace per iteration.

Never configure `androidx.benchmark.junit4.SideEffectRunListener` as a runner argument in the personal-device benchmark lane. In AndroidX Benchmark 1.5.0-alpha07 it disables 41 unrelated packages, including Play Store and Google Play services, then unconditionally enables every package without restoring prior state. The required benchmark library may still package the listener and `DisablePackages` classes; class presence is harmless because no runner argument instantiates them. Source guards and packaged-runner manifest verification prove the listener is unconfigured. The benchmark app APK separately isolates its application ID and storage and removes production deep links, App Links verification, install/network permission, and FileProvider.

## Production App Data Safety

Treat the production package `com.theoriacodex` and its private data as protected user data. Before running any Gradle task, Android Studio action, script, ADB command, connected test, baseline-profile collection, or benchmark that can install, uninstall, replace, clear, downgrade, or launch an APK, prove the packaged application ID and signing lane first. Build-only assembly tasks do not touch a device, but do not infer install safety from a task or variant name.

Every debug-signed or device-testable application variant must use a non-production application ID: Debug uses `com.theoriacodex.debug`, macrobenchmark uses `com.theoriacodex.benchmark`, release acceptance uses `com.theoriacodex.acceptance`, and baseline-profile collection uses `com.theoriacodex.baselineprofile`. Keep automated source and packaged-artifact guards for these identities. Never run a connected lane on a personal device if its target resolves to `com.theoriacodex`, and never use `adb uninstall`, `pm clear`, signature-mismatch uninstall/reinstall, or an install flag that removes production data unless the user explicitly authorizes that exact destructive production-package action after being warned that saved data can be lost.

For a new or changed device command, use a host-only dry run to inspect its task graph, verify output metadata or the packaged manifest for every APK it can install, and confirm that no production-ID target or package-mutating listener is configured. If that proof is incomplete, stop before connecting to the device. Use `installDebug` only for the isolated Debug app; production releases must be installed only through the signed release/update path.

## Local Statistics

`StatisticsRepository` owns forward-only, on-device lifetime counters; it must not duplicate current Codex library state. Saved post, saved source, saved tag, and top-Codex-source statistics are live projections of the active profile's visible Codices, deduplicated by canonical `Post.id`. Lifetime counters begin when the statistics store is introduced and are not backfilled from clearable Recents data.

Record events only at their authoritative outcome: accepted root Search and For You executions, one-shot Viewer page visibility, successful post-URL clipboard copies, completed Codex saves originating from For You, and Codex detail route entries. Pagination, failed or stale work, browser opens, tag copies, and recomposition do not count. Unified and Multi-Search source rows describe participation and may therefore sum above the overall search total. Watched and saved tags remain source-aware.

Foreground timing uses process lifecycle plus monotonic elapsed time. Total app time includes every foreground route, while Browsing, Watching, and Codex are mutually exclusive route categories; Settings remains total-only. Statistics writes are best-effort side effects and must never turn a successful user action into a feature failure. Keep the typed store schema, R8/Gson wire manifest, repository tests, and projection tests synchronized whenever the durable aggregate changes.

## Secondary Chrome And Feed Filters

Codex detail, Creator Profile, Viewer, and future secondary routes use `SecondaryScreenAppBar` for the shared left Back, center title/context, and right action geometry. Search, For You, and Creator Profile use `FeedFilterSheet` and `FeedFilterFab`; filter values and refresh behavior remain route-owned, while the shared FAB shows active state through tint and accessibility state rather than a persistent summary row.

FAB filter/sort restore state lives in `UiRestoreRepository` and is loaded by the app-shell `FeedFabRestoreRegistry` before a feed route renders. Search and For You use separate top-level keys; Creator Profile keys include source plus creator identity; Codex detail keys include the Codex ID. Keep query-owned Search sort/date/score state in the Search query owner rather than duplicating it in FAB restore storage. New FAB contexts must receive their own stable key so switching tabs or relaunching never leaks controls between feeds.

## Codex Collection Actions

`CodexListScreen` owns one collection-action sheet reached by both the compact tile overflow affordance and tile long-press; keep export/share, search, rename, and delete behavior in that shared surface rather than creating divergent entry-point logic. `CodexDetailScreen` owns explicit multi-post edit selection through `CodexEditSelection`, while long-press retains the full single-post action sheet. Do not add permanent overflow controls to individual feed or Codex post cards to expose these actions.

Codex Automatic rules are source-aware grouped canonical tag memberships owned by each user Codex. The shared collection-action sheet derives represented-tag counts from hydrated Codex posts and edits one source recipe at a time: every group is required with AND, while tags inside a group match with OR. Its available-tag picker renders one selected source at a time and filters that source locally; do not restack every provider's long tag list. Keep group indexes contiguous per source; removing the final tag from a group compacts every later group by one without changing another source's recipe. Legacy flat rules migrate into one OR group per source so their behavior is preserved. A transition to liked may add the post to matching Codices belonging to the active recommendation profile in the same Room transaction as Likes; unliking, disabling a rule, or clearing Likes must never remove those user-Codex memberships. The system Likes Codex does not need Automatic rules because it already receives every liked post.

`MigrationTestHelper.runMigrationsAndValidate` returns a raw validation connection with SQLite foreign-key enforcement disabled. Migration tests that assert `ON DELETE CASCADE` behavior must enable `PRAGMA foreign_keys = ON` on that connection before performing the delete.

Codex detail filtering is route-local and uses the shared feed filter FAB/sheet. Repository observation remains the authority for Newest, Oldest, and By source ordering; local filters preserve that order. Source options are the enabled sources represented in the collection. Language and Full Color are offered only when enabled NHentai or Hitomi posts are represented, and unsupported-source posts do not match an active capability filter. Animated-duration resolution runs through the navigation-scoped bounded enrichment owner without rewriting durable Codex snapshots. Viewer launch must receive the exact visible ordered post list and index from the screen rather than reopening the unfiltered repository snapshot.

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
