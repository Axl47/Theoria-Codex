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

## Search Tag Suggestions

Search autocomplete is local-first: query the complete bounded tag lexicon before applying a result limit, publish those matches before the network debounce, and let the bounded provider refresh improve the same generation later. Reuse fresh exact source/prefix/scope requests, keep Unified provider calls concurrent, and preserve cancellation plus stale-generation rejection. Ranking must prefer exact, prefix, then contained matches; compare counts only within one provider/relevance band and fairly interleave Unified sources whose corpus sizes are not comparable.

Suggestion origins are storage policy, not user-facing taxonomy. Keep active Trending membership separate from seed, autocomplete, seen, featured, and count-lookup knowledge; replacing Trending must not delete another origin for the same tag. Never display raw cache labels such as `seed`, `seen`, or `pixiv_tags_page`.

Pixiv suggestion identity is its native tag text. A provider translation is an alternate match/display label only, and selecting the suggestion must still submit the native value. Request Pixiv's current locale, retain its authoritative response order when counts are absent, and persist native/alternate pairs without learning them as two independent recommendation interests.

## Loading Responsiveness

Unified provider execution has a 15-second source-local deadline, including query preparation. Gelbooru compatibility lookups belong inside its provider job, use a bounded expiring mapping cache, and must never hold back another source. Continuations retain the exact prepared query, including exclusions enforced locally. Grouped fallback branches overlap with at most two requests; cold recommendation-tag acquisition is bounded and concurrent while preserving deterministic source and branch ordering.

Search publishes local autocomplete from background work before the network debounce, then admits incremental remote improvements through the same generation guard. Cache normalization and lookup snapshots are process-only; do not add derived fields to durable JSON. Same-query refresh keeps existing grid nodes attached. Required applied-query and scroll commits precede result acceptance; the two applied-query keys commit together, while admitted Recents/statistics bookkeeping follows publication and survives cancellation of the completed root job.

Viewer resolves only its current and immediately following post; leaving that window cancels obsolete resolution and resets its pending state without a user-facing failure. Replacing media invalidates both active and completed preview-prefetch work for that post. GIF prefetch, Movie decoding, and decoder fallback share the bounded raw-byte store (128 MiB / 64 files, 64 MiB per GIF); acquisition uses the cancellable source transport and request-scoped headers. Cache writes are best-effort. Cached loading previews must match the selected gallery page and must not start additional network downloads.

## Search Scroll Restoration

The Search route applies persisted scroll position once when the route is restored or re-entered. Page appends must not retrigger that restoration from a changed result count, or pagination will replay the initial saved position and jump the grid to the top. Keep route-entry restoration separate from page-loading state. Unified execution may retain `EXCLUDED` source statuses for orchestration diagnostics, but the UI status row should only render actionable provider failures.

`UiRestoreRepository` is the sole live Search scroll store. `query_store.json` owns applied queries only; its pre-F05 `scrollOffsets` field is a one-time DataStore migration input and is removed after a verified import. SearchViewModel owns debounce and registers a closeable scheduler that synchronously waits for its final DataStore write during ViewModel teardown before cancelling that scheduler. This deliberately trades a storage-operation-length teardown stall for a provable final flush; do not move that flush into the already-cancelled `viewModelScope` or add a lossy timeout.

The collapsed Search field renders applied context through its existing unfocused placeholder slot. Build that summary only from `applied` query/source state plus current visibility-filter state; never copy the summary into the real text input or present draft terms as applied. Keep it one line with ellipsis and do not add a separate applied-query row.

## Related Post Shelves

Search and For You may show one transient provider-native related shelf only after the local Like/Codex transaction commits a transition to liked. Derive support from `RelatedPostsSourceAdapter`, deliver the typed outcome to the exact originating route owner, preserve provider order, and reject stale generations. Never send a remote Pixiv bookmark or Gelbooru favorite.

Related posts are presentation-only: keep them outside canonical Search/FYP results, continuations, statuses, applied queries, recent searches, FYP history, and durable scroll state. Project the full-line shelf onto canonical post indices so Search restoration and both feeds' paging remain stable. A count-only Like change must not regenerate a non-empty FYP feed; the next legitimate root refresh trains from the updated Likes. Related Viewer streams are static `RELATED` sessions with no live paging or process-restored result substitution.

Related shelf pages are transient navigation history, not provider feed pages. Liking a post from the selected shelf page promotes it once into the owning canonical feed immediately before the shelf, removes it from every retained shelf page, advances the shelf anchor to that post, and loads a newly selected page seeded by it. Preserve older non-empty pages for bounded previous/next navigation, reject cross-page duplicate canonical IDs, and keep page changes out of durable feed pagination and scroll state.

A related shelf row is never a persistable Search scroll anchor. Projection changes must not restart scroll observation or replace the latest canonical post position with the shelf seed; if the shelf is first visible, retain the previous valid position. Bound the complete related-post load, including provider retries, to 15 seconds and convert only that deadline into the shelf Retry state. Route replacement, dismissal, and owner cancellation remain true cancellation and must not surface as timeout failures.

## Retired Viewer Translation

Viewer OCR and translation have been removed from Settings and Viewer, including model downloads, analysis, overlays, and remote requests. Legacy OCR preferences, translation lifetime counters, and the Room translation cache schema remain for stored-data compatibility only; do not wire them back into runtime features. The historical `deploy/libretranslate/` gateway is not used by the app; changing the live service requires separate authorization.

## Recents Section Identity

Watched and Codex are independent Recents memberships, not mutually exclusive labels derived from the latest Viewer origin. One canonical post may have one row in each section while sharing the same `posts` payload. Keep section in the `recent_watched` identity, preserve exact launch origin as row metadata, carry the section explicitly through `ViewerLaunchContext` when reopening from Recents, and clear or route by section. The combined All activity view may collapse duplicate canonical posts to the newest membership, but the filtered sections must retain both.

FYP Recents stores recommendation searches, never the posts returned by those searches. `ForYouCoordinator` records one FYP search only after an accepted root generation, using its exact tags per source, participating-source order, sort, and seed identity; pagination, stale/cancelled/failed work, recomposition, and Viewer activity must not create FYP search entries. Reopening dispatches a typed replay through the navigation-owned For You route before moving tabs, and that replay supersedes any automatic refresh so the saved per-source query format wins. On a cold route owner, buffer replay until both the first authoritative environment synchronization and the first source-availability reconciliation complete; either startup pass can otherwise clear or cancel the historical request. An explicit replay seed also takes precedence over the no-likes training empty state. Keep FYP independently clearable with exact Undo and include it in All, while direct For You post viewing remains normal Watched history. The legacy post-section enum value remains decodeable for compatibility, but those obsolete rows stay hidden from combined activity.

Recent Searches preserve the accepted execution kind and participating sources. Temporary multi-source executions are recorded and reopen as Multi-Search with their explicit source set, but they remain excluded from durable applied-query and Search-scroll restoration. Room stores this metadata in the versioned recent-search wrapper inside the existing query payload column; keep decoding the legacy raw Query payload so existing history remains readable without a database migration.

Watched Recents retains one-based multi-media progress as the highest media number ever made visible for that post and section. Viewer post visibility remains the one-shot owner of lifetime watched statistics; later media-page changes update only the monotonic Recents progress and must not reorder history or rewrite the shared post payload. Existing rows start at media 1, and only the Watched card badge presents `highest/total`; Codex and other card surfaces keep the total-only badge.

## Feed Autoplay Performance

Viewer animation progress belongs to its renderer, not the whole route state. GIF and Ugoira share lifecycle-aware monotonic frame timing; background time must not advance playback on resume. Keep route play/pause/rate/restart commands and compact restoration separate from per-frame state. Recommendation generation prepares one tag model per source and root attempt off Main; blacklist retries sample that model without rebuilding it or fetching unused fallback tags.

Search, For You, and Creator Profile share `FeedPageDemand` through `rememberFeedPageDemand`. It acknowledges settled request generations even when pages add no posts, pauses for loading/duration decisions, and limits automatic draining to three pages between user demands. A reachable Continue action resets the budget. Preserve provider continuation, canonical scroll mapping, partial-source failure isolation, and historical FYP replay without current Likes.

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

Visible video playback must bypass cache ranges locked by another writer and read upstream instead; `FLAG_BLOCK_ON_CACHE` is reserved for background prefetch. Otherwise a stalled feed or prefetch download can hold Viewer playback indefinitely without a player error. Preserve request-scoped headers and shared cache identity in both paths.

Numeric startup, Search concurrent-autoplay, and Viewer-swipe measurements live in the separate `macrobenchmark` module and target `benchmarkRelease`. Run `:macrobenchmark:connectedBenchmarkReleaseAndroidTest` only on physical hardware; build-only or emulator results are not performance proof. The offline fixture activity exists only in `app/src/benchmarkRelease`, runs in `:benchmarkFixture`, skips production container startup in that exact compile-time-enabled process, and publishes benchmark-only playback diagnostics. Preserve the complete `macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<device>/` directory because it contains the JSON result and one Perfetto trace per iteration.

Regenerate checked-in baseline/startup profiles only after structural app refactors settle, then run the exact final benchmark. D8 missing-profile entries mean the profile is stale and invalidate conclusions about profile-guided startup until it is refreshed through the isolated `com.theoriacodex.baselineprofile` lane. Duration benchmarks must distinguish overlapping per-key workload spans from one request-to-settlement batch makespan span; never compare a sum of concurrent per-key work with a legacy single batch span as though they measure the same quantity.

Never configure `androidx.benchmark.junit4.SideEffectRunListener` as a runner argument in the personal-device benchmark lane. In AndroidX Benchmark 1.5.0-alpha07 it disables 41 unrelated packages, including Play Store and Google Play services, then unconditionally enables every package without restoring prior state. The required benchmark library may still package the listener and `DisablePackages` classes; class presence is harmless because no runner argument instantiates them. Source guards and packaged-runner manifest verification prove the listener is unconfigured. The benchmark app APK separately isolates its application ID and storage and removes production deep links, App Links verification, install/network permission, and FileProvider.

## Production App Data Safety

Treat the production package `com.theoriacodex` and its private data as protected user data. Before running any Gradle task, Android Studio action, script, ADB command, connected test, baseline-profile collection, or benchmark that can install, uninstall, replace, clear, downgrade, or launch an APK, prove the packaged application ID and signing lane first. Build-only assembly tasks do not touch a device, but do not infer install safety from a task or variant name.

Every debug-signed or device-testable application variant must use a non-production application ID: Debug uses `com.theoriacodex.debug`, macrobenchmark uses `com.theoriacodex.benchmark`, release acceptance uses `com.theoriacodex.acceptance`, and baseline-profile collection uses `com.theoriacodex.baselineprofile`. Keep automated source and packaged-artifact guards for these identities. Never run a connected lane on a personal device if its target resolves to `com.theoriacodex`, and never use `adb uninstall`, `pm clear`, signature-mismatch uninstall/reinstall, or an install flag that removes production data unless the user explicitly authorizes that exact destructive production-package action after being warned that saved data can be lost.

For a new or changed device command, use a host-only dry run to inspect its task graph, verify output metadata or the packaged manifest for every APK it can install, and confirm that no production-ID target or package-mutating listener is configured. If that proof is incomplete, stop before connecting to the device. Use `installDebug` only for the isolated Debug app; production releases must be installed only through the signed release/update path.

Baseline-profile collection installs both the isolated target APK and a self-instrumenting test APK. Keep their packaged IDs distinct (`com.theoriacodex.baselineprofile` and `com.theoriacodex.baselineprofile.test`) and verify both before connected collection; a shared ID makes Android treat the version-code-0 test APK as a downgrade of the target.

## Local Statistics

`StatisticsRepository` owns forward-only, on-device lifetime counters; it must not duplicate current Codex library state. Saved post, saved source, saved tag, and top-Codex-source statistics are live projections of the active profile's visible Codices, deduplicated by canonical `Post.id`. Lifetime counters begin when the statistics store is introduced and are not backfilled from clearable Recents data.

Record events only at their authoritative outcome: accepted root Search and For You executions, one-shot Viewer page visibility, successful post-URL clipboard copies, completed Codex saves originating from For You, and Codex detail route entries. Legacy translation counters remain decodeable but are no longer recorded or displayed. Pagination, failed or stale work, browser opens, tag copies, and recomposition do not count. Unified and Multi-Search source rows describe participation and may therefore sum above the overall search total. Watched and saved tags remain source-aware.

ViewerViewModel owns per-session/per-post visibility admission and the recording job. Renderer or Activity reattachment must not count an existing visit again, and an admitted write must not be cancelled by a same-session payload update; a genuinely new Viewer session may count the same post again.

Foreground timing uses process lifecycle plus monotonic elapsed time. Total app time includes every foreground route, while Browsing, Watching, and Codex are mutually exclusive route categories; Settings remains total-only. Statistics writes are best-effort side effects and must never turn a successful user action into a feature failure. Keep the typed store schema, R8/Gson wire manifest, repository tests, and projection tests synchronized whenever the durable aggregate changes.

## Secondary Chrome And Feed Filters

Codex detail, Creator Profile, Viewer, and future secondary routes use `SecondaryScreenAppBar` for the shared left Back, center title/context, and right action geometry. Search, For You, and Creator Profile use `FeedFilterSheet` and `FeedFilterFab`; filter values and refresh behavior remain route-owned, while the shared FAB shows active state through tint and accessibility state rather than a persistent summary row.

FAB filter/sort restore state lives in `UiRestoreRepository` and is loaded by the app-shell `FeedFabRestoreRegistry` before a feed route renders. Search and For You use separate top-level keys; Creator Profile keys include source plus creator identity; Codex detail keys include the Codex ID. Keep query-owned Search sort/date/score state in the Search query owner rather than duplicating it in FAB restore storage. New FAB contexts must receive their own stable key so switching tabs or relaunching never leaks controls between feeds.

Search keeps its Animated-only and animated-duration controls scoped by the selected source (with Unified as its own scope) inside the Search FAB state. Shared visibility controls such as Liked, Saved, and Watched remain route-wide; never let an animation choice made for one provider filter another provider's results.

Post action menus and Viewer Info share the lazy list owned by `PostTagActionSection`; pass their header and footer into that list rather than wrapping it in another vertical scroller. Keep tag-count cache scans and provider enrichment off Main, and reserve the count line so arriving counts do not resize tag rows.

## Codex Collection Actions

Collection saves use `CodexRepository.addItems` for one atomic membership transaction before best-effort caching. `CodexSaveViewModel` owns direct-save jobs and typed completion feedback outside sheet composition. Routine Post updates share `mergeSharedPostPayload`; sparse snapshots cannot erase resolved media, and sparse cache writes must preserve usable existing offline bytes. `LikesRepository` is observation-only; production Like mutations use `CodexLikesTransactions` to keep the system Codex consistent.

Collection overview uses `observeCodexSummaries` for selected-profile counts and bounded cover Posts; full tag options load only for the open collection action sheet through `CodexCollectionSource`. Keep JSON projection and cover filesystem work off Main. Live orphan cleanup checks only membership IDs removed by the transaction; the global sweep is reserved for legacy migration. Room schema 9 adds the reverse Likes lookup index without changing stored payloads.

`CodexListScreen` owns one collection-action sheet reached by both the compact tile overflow affordance and tile long-press; keep export/share, search, rename, and delete behavior in that shared surface rather than creating divergent entry-point logic. `CodexDetailScreen` owns explicit multi-post edit selection through `CodexEditSelection`, while long-press retains the full single-post action sheet. Do not add permanent overflow controls to individual feed or Codex post cards to expose these actions.

Codex Automatic rules are source-aware grouped canonical tag memberships owned by each user Codex. The shared collection-action sheet derives represented-tag counts from hydrated Codex posts and edits one source recipe at a time: every group is required with AND, while tags inside a group match with OR. Its available-tag picker renders one selected source at a time and filters that source locally; do not restack every provider's long tag list. Keep group indexes contiguous per source; removing the final tag from a group compacts every later group by one without changing another source's recipe. Legacy flat rules migrate into one OR group per source so their behavior is preserved. A transition to liked may add the post to matching Codices belonging to the active recommendation profile in the same Room transaction as Likes; unliking, disabling a rule, or clearing Likes must never remove those user-Codex memberships. The system Likes Codex does not need Automatic rules because it already receives every liked post.

`MigrationTestHelper.runMigrationsAndValidate` returns a raw validation connection with SQLite foreign-key enforcement disabled. Migration tests that assert `ON DELETE CASCADE` behavior must enable `PRAGMA foreign_keys = ON` on that connection before performing the delete.

Codex detail filtering is route-local and uses the shared feed filter FAB/sheet. Repository observation remains the authority for Newest, Oldest, and By source ordering; local filters preserve that order. Source options are the enabled sources represented in the collection. Language and Full Color are offered only when enabled NHentai or Hitomi posts are represented, and unsupported-source posts do not match an active capability filter. Animated-duration resolution runs through the navigation-scoped bounded enrichment owner without rewriting durable Codex snapshots. Viewer launch must receive the exact visible ordered post list and index from the screen rather than reopening the unfiltered repository snapshot.

## Releases

GitHub prereleases are created only by pushing an annotated `vX.Y.Z` tag. The tagged commit must declare the same `versionName`, its calculated Android `versionCode` (`1_500_000_000 + major * 10_000 + minor * 100 + patch`), and a curated `release-notes/vX.Y.Z.md` file. Do not use a low sequential version code: existing installs and the updater already compare against this high SemVer-derived range.

Release preparation must run `python3 scripts/check_hotspots.py --base <previous-release-tag>` before committing. Detekt alone does not enforce the frozen production-file line budgets, and a multi-commit push makes GitHub evaluate every change since the previously pushed SHA.

`actions/checkout` can replace its local tag ref with the peeled commit during a tag-push workflow. The prerelease workflow must explicitly refetch `refs/tags/$GITHUB_REF_NAME` before proving it is annotated and that its annotation matches the checked-in release notes; otherwise a valid annotated tag can fail the release gate.

Use the repo-local `$theoria-release` skill in `.codex/skills/theoria-release/` whenever preparing or publishing a release. It drafts notes before making changes and requires a separate explicit publish instruction before it creates or pushes a tag.

Release JSON verification must run through `:app:verifyReleaseJsonContracts` and `:app:verifyReleaseAcceptanceJsonContracts`. Those tasks consume AGP's public `SingleArtifact.OBFUSCATION_MAPPING_FILE`; AGP 9.1.1 exposes no public seeds artifact, so the matching `outputs/mapping/<variant>/seeds.txt` remains a separate explicit task input. Do not invoke the Python verifier against a guessed intermediates path or infer seeds beside the intermediate mapping.

## Testing Evidence

Shared test-only Kotlin roots use `AndroidSourceSet.kotlin.directories`, not `java.srcDir`; AGP's built-in Kotlin otherwise silently omits those custom Kotlin sources. `app/src/fixtures` belongs only to Debug and benchmarkRelease. It supplies controlled external providers and isolated storage to the real app graph; do not introduce a production fixture switch. The shared Room upgrade scenario lives in `core-data-android/src/sharedTest/kotlin` and runs through both Robolectric and device wrappers.

Keep test names proportional to the layer they execute. Source/import/packaged-manifest guards are architecture evidence; they must not substitute for UI navigation, advancing media, cancellation, or durable-state assertions. Undo tests must observe removal before accepting Undo. The real-screen journeys distinguish Activity recreation from a fresh graph reopened over the same durable files. OCR host tests using a plain Application must initialize ML Kit with its idempotent context initializer because the manifest provider is bypassed. Tests that execute Coil must provide a usable Main dispatcher and bounded completion; do not block Robolectric's paused Main looper with `runBlocking` while waiting for Coil.

`scripts/verify_device_apk.py` verifies the packaged isolated ID, expected debuggability and actual configured debug certificate before installation. Release acceptance additionally executes variant-only JSON write/read checks across separate process launches. Performance changes use `scripts/verify_performance_device.sh`: calibration requires three distinct complete physical runs of one APK; comparison binds fixture code/assets, runner APK, device, OS and compilation mode and preserves every iteration trace. Incomplete, mismatched or thermally throttled evidence is not a passing comparison. The empirical calibration envelope is a regression signal, not a statistical confidence claim.

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

## Source Recovery And Watching Controls

Search source retries preserve the applied query identity, accepted grid order, and other providers' continuation. Keep failed Unified page tokens available for retry; a single-source retry uses its exact native query, including facets, rather than the portable Unified query. Retrying must not record another root Search/Recents event.

`ImageRef.videoVariants` contains alternate full-video renditions of one gallery item. It must not add gallery pages or replace canonical media used by duration fingerprints. Viewer and downloads select independently, carry the selected MIME, and retain request-scoped source headers. Sparse updates may retain variants only for the same media identity. Animation exports use application transport and must honor the same download network preferences as DownloadManager requests.

Followed is a virtual Codex with ID `system:followed`, backed by existing local creator memberships rather than saved-post rows. Its source and source-qualified author selections use OR within each set and AND between sets, and persist under its own Codex FAB key. Keep provider continuations independent, reject stale membership/filter generations, and preserve successful branches on failure. Do not expose saved-collection deletion, post removal, export, automatic tags, or save destinations for this virtual collection.

Creator follows are local, bounded Settings memberships. Manual checks use two concurrent provider calls with a 15-second deadline per creator. Durable check results must match the membership ID that started the request, so unfollowing/re-following cannot admit an older request. New-post counts describe the provider's latest page; accepted creator visits acknowledge visible canonical IDs without a remote follow or notification subscription.

Viewer completion events carry session, media key, and load generation. Next in queue advances through the current gallery and then the loaded post stream; it stops at its end. MediaSession belongs only to the current Viewer player. Preserve playback during an explicit PiP transition and while PiP is visible, but close the session and pause playback when the Activity stops. Feed preview players never own MediaSessions.

Adaptive navigation retains one content slot across bottom-bar/rail changes, and feed lanes derive from available pane width while preserving canonical indices and simultaneous visible autoplay. Build and Robolectric evidence do not establish live PiP transitions or hardware playback performance; device acceptance still requires the isolated packaged-ID preflight.
