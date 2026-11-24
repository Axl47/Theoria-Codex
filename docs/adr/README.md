# Architecture decision records

Kotlin and Java sources carry no comments. When code depends on a decision a reader could not work out from
the code itself, such as a platform constraint, a measured tradeoff or a rejected alternative, the decision is
recorded here instead. `scripts/check_comments.py` enforces the ban; the comments that predate it are counted
in `config/quality/comment-baseline.json`, which may only shrink.

## Format

One file per decision, named `NNNN-kebab-case-title.md` and numbered in order. Copy
[`template.md`](template.md): context, decision, consequences, and the code the decision governs, named by
file and symbol so a reader can find it. Group related code under one decision rather than writing one
record per call site. Name constants rather than copying their values, and avoid claims that expire, such
as "the latest release". When a decision changes, edit its record; when it is reversed, mark it superseded
and link the record that replaces it.

## Index

| ADR | Decision |
| --- | --- |
| [0001](0001-comment-links.md) | Comment links use the site's anchor when it has one, else a fragment browsers ignore |
| [0002](0002-coroutine-and-diagnostics-rules.md) | Shared requests, cancellation, diagnostics and off-main checks follow fixed rules |
| [0003](0003-cross-source-tags.md) | For You trains across sources by translating tags, with foreign likes capped |
| [0004](0004-custom-site-profiles.md) | Custom booru sites get ids compatible with existing encodings, validated HTTPS roots and secret-safe credentials |
| [0005](0005-picture-tagger-thresholds.md) | Predicted picture tags are a separate tag source with per-use score thresholds |
| [0006](0006-post-identity-and-dedup.md) | One file or picture shown once: posts are deduplicated by ID, MD5 and a banded perceptual hash |
| [0007](0007-series-sources-and-extensions.md) | Series come from Mihon and Aniyomi extensions installed into app storage, trusted by pinned signing key |
| [0008](0008-source-adapter-contracts.md) | Source capabilities are optional adapter interfaces with throw-on-failure and null-for-absent results |
| [0009](0009-stored-data-compatibility.md) | Persisted identifiers, hashes and legacy fields keep their original form |
| [0010](0010-tag-and-text-normalization.md) | Tag spelling is normalized per source dialect and provider quirks are undone at the edge |
| [0011](0011-unified-search-orchestration.md) | Unified search publishes early, merges stably and shapes requests per source |
| [0012](0012-room-explicit-migrations.md) | The Room database upgrades only through explicit, tested migrations |
| [0013](0013-room-read-paths-and-projections.md) | Room reads avoid transactions and reuse decoded posts |
| [0014](0014-series-backup-merge.md) | Restoring a series backup only adds to what the device holds |
| [0015](0015-series-entry-storage.md) | Series entries are kept, never deleted, when a source stops listing them |
| [0016](0016-shared-post-storage.md) | Posts are stored once, sparsely and compared by decoded value |
| [0017](0017-visual-similarity-storage.md) | Visual signatures and For You verdicts are stored with their own lifecycle |
| [0018](0018-codex-library-transactions.md) | Cross-Codex and Likes operations commit atomically and Undo authority is process-only |
| [0019](0019-followed-and-saved-new-post-marks.md) | Follows and new-post marks are local, identity-keyed and rate-limited across restarts |
| [0020](0020-for-you-retired-searches.md) | Saved For You searches are converted to ordinary source searches without displacing existing ones |
| [0021](0021-local-state-files.md) | Small local state files reject newer schemas, skip unreadable entries and keep lists as raw JSON |
| [0022](0022-offline-media-store.md) | Offline copies commit through a manifest and survive undecodable data without losing owners |
| [0023](0023-post-snapshots.md) | Post snapshots drop device-bound data and merging never erases known data |
| [0024](0024-profile-backup-format.md) | Profile backups are a versioned, strictly validated document written at the lowest version that fits |
| [0025](0025-profile-backup-restore.md) | Restore merges through a durable intent and only adds to what the device has |
| [0026](0026-profile-deletion-journal.md) | Profile deletion runs from a fail-closed durable intent and blocks writes to retired profiles |
| [0027](0027-series-download-queue.md) | Chapter downloads run from a two-lane queue with one worker |
| [0028](0028-series-storage-and-switching.md) | Series entry lists, progress and source switches are stored conservatively |
| [0029](0029-source-weights-normalization.md) | Source weights are normalized without drift and new sources get an even share |
| [0030](0030-statistics-recording.md) | Usage statistics split by local calendar day, merge by maximum and seed tag counts once |
| [0031](0031-custom-site-profile-storage.md) | User-added site profiles are stored without credentials, skip invalid records, and merge on restore without the site limit |
| [0032](0032-datastore-open-and-recovery.md) | Stores open through a startup gate, refuse newer schemas, set unreadable files aside before resetting, and close by waiting for their release |
| [0033](0033-durable-json-configuration.md) | All saved data uses one kotlinx Json configuration that keeps Gson-era behaviour |
| [0034](0034-golden-files-pin-stored-formats.md) | Golden files are bytes real builds wrote and are never regenerated to pass a change |
| [0035](0035-repository-state-cells.md) | Repository rules run over a StateCell so in-memory and durable backings share one code path |
| [0036](0036-settings-persistence-rules.md) | Settings are admitted before persistence and change with their derived source weights in one transaction |
| [0037](0037-statistics-thumbnail-store.md) | Statistics thumbnails are private files owned by the top lists and fetched only for promoted or opened posts |
| [0038](0038-stored-record-compatibility.md) | Durable records are separate from domain models and keep decoding every shape this app ever stored |
| [0039](0039-tachiyomi-backup-import.md) | Mihon-family backups are decoded from their protobuf wire format and import their followed or started series |
| [0040](0040-custom-site-probing.md) | Adding a custom site runs a bounded read-only probe over protocol families |
| [0041](0041-danbooru-adapter.md) | The Danbooru adapter works within guest limits and id paging |
| [0042](0042-gelbooru-adapter.md) | The Gelbooru adapter tolerates per-fork API differences and latches detected quirks |
| [0043](0043-hitomi-provider.md) | Hitomi is read from Nozomi indexes with single-flight, version-bound caches and in-memory tokens |
| [0044](0044-nhentai-adapter.md) | nhentai falls back to mirrors for metadata but never for comments |
| [0045](0045-paging-by-raw-records.md) | A page is full when the raw record count says so, before filtering |
| [0046](0046-pixiv-provider.md) | Pixiv credential refreshes are serialized and fenced by a session revision |
| [0047](0047-provider-health-probes.md) | Provider health probes report CANNOT_CHECK behind a challenge and keep public output free of provider data |
| [0048](0048-provider-media-urls.md) | Provider media loads over HTTPS and prefers small renditions for cards |
| [0049](0049-source-calls-off-main-thread.md) | Source entry points run on a background dispatcher through sourceCall |
| [0050](0050-source-host-outages-and-challenges.md) | Host outages are tracked per host and Cloudflare checks are passed by a shared hidden browser |
| [0051](0051-source-http-transport.md) | Source HTTP stays HTTPS-only, byte-exact, unqueued and cancellable |
| [0052](0052-kvs-site-scraping.md) | KVS sites list latest posts through the listing when RSS thumbnails fail, and read lazy thumbnails from data-rd-src |
| [0053](0053-paheal-source-limits.md) | Paheal is excluded from hash lookup and infers file type from data attributes |
| [0054](0054-source-parsing-compat.md) | Source parsers keep provider and stored-data compatibility |
| [0055](0055-aniyomi-stream-resolution.md) | Anime streams resolve from all servers concurrently, with per-server and list time limits |
| [0056](0056-extension-binary-compatibility.md) | The host reproduces the Mihon and Aniyomi extension API under the names extension binaries expect |
| [0057](0057-extension-challenge-handling.md) | Extensions get a Mihon-like HTTP client that shares browser cookies and never lets a challenge page through |
| [0058](0058-extension-device-harness.md) | The real-extension device harness reports text only and measures extensions without the app's browser |
| [0059](0059-extension-image-tags.md) | Extension images are fetched through the source's own client, selected by an HMAC-signed address tag |
| [0060](0060-extension-isolation-and-failures.md) | Extension code runs off the caller's thread, and its failures become source failures without leaking detail |
| [0061](0061-extension-preferences-storage.md) | Extension preferences live in no-backup JSON files, written before apply returns |
| [0062](0062-extension-registry-loading.md) | The registry loads extensions lazily, per source lookup, and publishes one consistent offer |
| [0063](0063-extension-repos-and-install.md) | Extensions install only from a repo's named key, into a private folder, and load only from verified read-only copies |
| [0064](0064-extension-source-state.md) | Adapters hand sources back their own objects, memos and filters, and merge details additively |
| [0065](0065-extension-source-ids.md) | Source ids are the first eight bytes of MD5 of name/lang/versionId, as Mihon and Aniyomi derive them |
| [0066](0066-creator-feeds.md) | Account creator feeds park their lists, and Follow records the uploads list whichever tab is shown |
| [0067](0067-custom-sites-controller.md) | Custom site verification and setup never touch library data and bind cookies to the in-app browser |
| [0068](0068-duration-filter.md) | Duration filters reveal posts in stable order and schedule only the demand that matters |
| [0069](0069-feed-paging.md) | Feeds drain pages within a bound, except to reach a saved place, and merge without reordering |
| [0070](0070-for-you-deck-steering.md) | The For You deck deals deterministically from weighted lanes and steers from this session's swipes only |
| [0071](0071-for-you-interest-picking.md) | For You interests are weighed once per source and picked from proven, co-occurring tags |
| [0072](0072-image-text-and-file-tags.md) | Tags about text on the image or about the file are never counted as interests |
| [0073](0073-main-thread-work-checker.md) | Main-thread work is reported once per call site and counted without limit |
| [0074](0074-media-quality-and-kind.md) | Media policy decides from what the media offers, not from source names |
| [0075](0075-perceptual-hash.md) | Picture hashes match Python's imagehash on grey input and skip flat pictures |
| [0076](0076-post-comments-controller.md) | Viewer comments prefetch only the first page, keep loaded lists, and focus a linked comment with a page cap |
| [0077](0077-related-shelf.md) | Related shelves anchor to their seed, keep their seeds visible, and are never restored |
| [0078](0078-save-target-ranking.md) | Save targets are ranked once, with this session's saves and recent saves lifted above tag relevance |
| [0079](0079-search-draft-rules.md) | Draft edits keep source-valid terms and tell the user what a change removes |
| [0080](0080-search-execution-and-cache.md) | A search is identified by query and source scope, and cached first pages are filtered to enabled sources |
| [0081](0081-search-history-and-scroll-restoration.md) | Search history keeps settled searches only, and scroll restoration yields to the user |
| [0082](0082-search-request-lifecycle.md) | Search requests end through one reducer that keeps results on screen and ignores foreign answers |
| [0083](0083-search-suggestions-and-omnibox.md) | Suggestions interleave providers by relevance band and the omnibox places matches among tag rows |
| [0084](0084-search-visibility-filters.md) | Date and length filters run on the device with one animated-media rule for every source |
| [0085](0085-shared-tag-affinity-inputs.md) | Affinity inputs are built once per profile from its library and rebuilt only when the library changes |
| [0086](0086-statistics-attribution.md) | Statistics keep every recorded post, avoid reading posts on timer ticks, and attribute only direct Codex launches |
| [0087](0087-tag-affinity-scoring.md) | Tag affinity compares kept posts with watched posts and discounts universal tags |
| [0088](0088-training-tags.md) | Training tags keep each source's vocabulary and use typed taxonomy to find general tags |
| [0089](0089-triage-hide-rules.md) | Hidden tags match within their own vocabulary and the picture tagger judges only sparsely tagged posts |
| [0090](0090-viewer-and-feed-playback.md) | Feed players start after a short delay and Viewer rotation is offered only where it gains space |
| [0091](0091-feed-chrome-stability.md) | Feed chrome avoids layout jumps, per-frame recomposition and noisy announcements |
| [0092](0092-frame-mixing.md) | Several pictures of a post fold into one analysis by weighted top-third scores |
| [0093](0093-in-app-browser-webview.md) | The in-app browser is a locked-down WebView that lives as long as its address and agent |
| [0094](0094-library-vision-backfill.md) | Library picture indexing is paced, thermally gated and bounded in retries |
| [0095](0095-lift-and-triage-contracts.md) | Card lift and triage hand-offs keep the long-click as the fallback and carry the grid's view |
| [0096](0096-picture-model-runtime.md) | The picture model runs on the CPU, loaded only while pictures arrive, with int8 embeddings |
| [0097](0097-picture-tagger-session-and-commits.md) | The picture tagger remembers results per session and never leaves answers behind a deleted model |
| [0098](0098-route-state-owners.md) | Route owners publish immutable state and keep large or Android objects out of saved state |
| [0099](0099-ui-test-fixtures.md) | Compose tests drain the main looper and step background work by hand |
| [0100](0100-undo-offers-and-deferred-removals.md) | Removals are hidden first and deleted when their lifetime ends, with one app-wide Undo offer |
| [0101](0101-visual-model-files.md) | Model files are pinned by SHA-256, renamed into place only when verified, and removed on any failure |
| [0102](0102-app-http-clients.md) | Every app HTTP client derives from one shared client, and the app learns hosts and IPv6 health to cut first-request waits |
| [0103](0103-diagnostics-log-and-usage.md) | The diagnostics log is redacted, bounded and written off the failing thread; data-usage counting exists only in debug builds |
| [0104](0104-duration-lookups.md) | Video lengths are looked up by one application-scoped coordinator, paced per source, and cached without changing outcomes |
| [0105](0105-feed-media-delivery.md) | Feed cards load the preview first, step up only to a downscaled rendition, and lazy media links are refreshed for booru families |
| [0106](0106-feed-state-retained-across-viewer.md) | Feed state outlives the Viewer, and feed work runs off the main thread without flashing or reshuffling |
| [0107](0107-frame-sampling-for-tagger.md) | Frame sampling for the picture tagger has a per-post byte budget and classifies each failure by whether a retry could change it |
| [0108](0108-media-disk-caches.md) | Disposable media caches are cleared by their owners, cached bodies that cannot decode are evicted, and downloads stage before publishing |
| [0109](0109-media-kit-device-and-fixture-tests.md) | Stream tests run against a captured loopback fixture, and device and coroutine test helpers work around known platform limits |
| [0110](0110-offline-media-jobs.md) | Offline copies are made by application-owned jobs admitted through one gate and validated before they count as available |
| [0111](0111-picture-hashing.md) | Posts get a picture hash from their cached still preview, within a listing budget, so reposts on other sites show once |
| [0112](0112-pixiv-ugoira-playback.md) | Pixiv animations play from a validated disk archive that downloads once, retries auth once and never touches offline copies |
| [0113](0113-post-download-queue.md) | Post downloads run from a durable queue that waits for sources, never duplicates a transfer and never deletes a finished file by itself |
| [0114](0114-site-browser-checks.md) | Browser checks run in one visible page, never leak credentials to the page, and media requests retry once with the user's key |
| [0115](0115-source-presentation-catalog.md) | One catalog holds source labels, headers, grouping and logos; custom sites install into it as a whole |
| [0116](0116-stream-playback-requests.md) | A playback request fixes the stream type, headers, tracks and subtitle loading from what the media offers |
| [0117](0117-video-cache-bounded-locks.md) | Background video readers lock the shared cache a chunk at a time, and only plain progressive web files use it |
| [0118](0118-video-http-transport.md) | Video HTTP is HTTP/1.1 only and abandons half-read responses instead of draining them |
| [0119](0119-video-player-tuning.md) | Players are pooled with deferred release, buffer lightly for feed previews, and seek by keyframe while dragging |
| [0120](0120-viewer-image-decoding.md) | Viewer images decode to hardware bitmaps, and tall pages are bounded by what the GPU can draw |
| [0121](0121-codex-save-relevance.md) | Codex relevance for save surfaces is shared, warm for a few seconds and never shows placeholders |
| [0122](0122-drag-gestures-act-on-release.md) | Drag-to-collect gestures act on the highlighted target at release and stay accessible |
| [0123](0123-failure-presentation.md) | Failure messages separate account problems, browser challenges and offline from plain retries |
| [0124](0124-feed-pipeline-and-grid.md) | Feed filtering and grids carry results forward and read hot state without recomposing everything |
| [0125](0125-file-mirror-index.md) | Copies of a post on other sites are grouped in memory from hashes learned this session |
| [0126](0126-post-tag-sheet-editing.md) | Tag sheets edit the Search draft without silently dropping terms and keep chips layout-stable |
| [0127](0127-quick-save-and-undo.md) | One-tap saves create Codices only when they run and Undo reverses exactly what the save added |
| [0128](0128-search-service-rules.md) | Search services own provider access only and keep bookkeeping ordered, capped and off the request path |
| [0129](0129-triage-deck-ordered-writes.md) | Triage decisions move the card at once and their writes run in order after the deck closes |
| [0130](0130-viewer-animated-playback.md) | Animated media share one clock, one timeline and a hold-to-scrub gesture without recomposing per frame |
| [0131](0131-viewer-return-scrolling.md) | Returning from the Viewer moves the feed as little as possible and keeps the originating post in place |
| [0207](0207-series-notifications.md) | Series notifications: one frozen channel, per-series tags, and a private trampoline |
| [0208](0208-viewer-holds-back-while-closing.md) | The Viewer holds a Back pressed while it closes, and its dismissal never suspends |
| [0209](0209-for-you-recent-skips.md) | For You leaves out posts skipped in the last 30 days |
| [0210](0210-host-test-compose-waits.md) | Host-test Compose waits drain the main looper |
| [0211](0211-viewer-owns-keep-screen-on.md) | The Viewer owns keep-screen-on |
| [0212](0212-settings-and-tag-term-behaviour.md) | Settings navigation, the Series sheet, and how post tag chips are built |
