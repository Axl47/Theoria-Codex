# 0043. Hitomi is read from Nozomi indexes with single-flight, version-bound caches and in-memory tokens

Status: Accepted

## Context

Hitomi's search pages are metadata shells and its media hosts, shards and base path come from a mutable `gg.js` file. The adapter reads the binary indexes directly. Several loads can race, and the provider's configuration can change between a listing and a media request.

## Decision

- The adapter reads Nozomi indexes, does typed intersections and subtractions, and hydrates only the page's IDs, one request per card (`DEFAULT_HYDRATION_CONCURRENCY`) multiplexed over one HTTP/2 connection. A missing index (404/416) is empty. Whole-index reads (`readWholeNozomi`, `readPrimaryRange`: random and secondary-membership indexes) accept a 206 only if it starts at byte zero and covers the complete declared body, and no whole index may hold more than its id limit. The paged primary reader instead accepts a 206 slice whose validated range starts at the requested offset.
- Caches (`HitomiGalleryManifestStore`, `HitomiGlobalIndexCache`, `HitomiRandomSnapshotCache`) are in-memory only, byte-budgeted, and share concurrent loads, which are cancelled only when every waiting caller has left. After a miss each rechecks the cache, because a load that overlapped the miss may have finished first. Missing or failed loads are not kept. The global index cache is bound to the active index version; a load finishing after a version change is returned but not kept. Random snapshots are reused by freshness for a first page and exactly by token for a continuation. A load timeout is distinguished from a missing gallery (null) by a `Result` wrapper.
- Page tokens live in a search's in-memory continuation, never in saved data, so only the current version decodes. `hitomiPageTokenJson` ignores unknown keys and omits nulls (`ignoreUnknownKeys`, `explicitNulls = false`, not `isLenient`); it is local to core-sources, which cannot see core-data's `DurableJson`.
- `HitomiMediaUrlResolver` derives candidates from `gg.js`, primary shard then alternate. After a media 404 the caller may call `refreshCandidates` with the failed `configurationVersion`; that refreshes at most once per failed version, even if the same configuration returns, and a provider or parse failure consumes the attempt, cancellation does not. `HitomiMediaLocation` keeps the `gg.js` version so recovery can tell which configuration produced a failed URL.
- Thumbnail paths derive from the hash alone and survive base-path changes, unlike full-size media; they carry no version, so the first full-size location identifies the media. Only still galleries use a thumbnail first; an animated card plays the animated file. Anime galleries are the ones the Viewer plays as video.
- Provider operations (search, post resolution, media recovery, autocomplete) run inside `hitomiCall`, which maps gallery failures (their own reason), `HitomiProtocolException` and `SourceHttpBodyTooLargeException` (PARSE) and `IOException` (NETWORK) to `SourceAdapterException`; other exceptions propagate. Entry points that do no provider I/O, such as `trendingTags` (empty), bypass it. The metadata parser is pure; transport lives in the adapter and URL acquisition in `HitomiPostMapper`.
- The gallery script assignment is extracted without regex features that differ between the desktop JVM and Android's ICU engine.
- Flags arrive as `"1"`/`""`, 1/0 or booleans (`truthy`). `female` and `male` are tag namespaces. All hosts are subdomains of `gold-usergeneratedcontent.net`.

## Consequences

Cache or token format changes need no migration. Loads must keep the recheck and share patterns when new caches are added.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiSourceAdapter.kt`: `HitomiSourceAdapter`, `DEFAULT_HYDRATION_CONCURRENCY`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiNozomiReader.kt`: `readWholeNozomi`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryManifestStore.kt`: `HitomiGalleryManifestStore`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiGlobalIndexCache.kt`: `HitomiGlobalIndexCache`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiRandomSnapshotCache.kt`: `HitomiRandomSnapshotCache`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiPageTokenCodec.kt`: `HitomiPageTokenCodec`, `hitomiPageTokenJson`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiMediaUrlResolver.kt`: `HitomiMediaUrlResolver`, `refreshCandidates`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiMediaUrls.kt`: `HitomiMediaLocation`, `hitomiThumbnailUrls`, `HITOMI_CDN_DOMAIN`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiPostMapper.kt`: `HitomiPostMapper`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiFailures.kt`: `hitomiCall`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiProtocol.kt`: `galleryAssignmentBody`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryModels.kt`: `truthy`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryMetadata.kt`: `parseHitomiGallery`, `HITOMI_FACETS`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiByteBudgetCacheTest.kt`: `oversize values are returned to the caller but never retained (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiCreatorFailureSourceAdapterTest.kt`: `protocol failures from public search and autocomplete are typed as parse errors (ADR 0043)`, `oversized range ignoring Nozomi response becomes a typed source error (ADR 0043)`, `oversized gallery and autocomplete bodies become typed source errors (ADR 0043)`, `a provider call turns IO failures into network errors and lets unexpected exceptions through (ADR 0043)`, `a media recovery whose configuration refresh fails becomes a typed source error (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiFacetHydrationSourceAdapterTest.kt`: `still gallery cards lead with a thumbnail that survives media recovery (ADR 0043)`, `search hydration is bounded ordered isolated and sparse while resolve is complete (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryManifestStoreTest.kt`: `only the last departing consumer cancels a shared load (ADR 0043)`, `a load deadline becomes a provider failure (ADR 0043)`, `missing and failed manifests never poison the cache (ADR 0043)`, `byte budget rejects a manifest larger than the entire cache (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryMetadataTest.kt`: `reads numbers sent as strings and ignores fields of the wrong type (ADR 0043, 0054)`, `reads tag namespace flags in every shape Hitomi sends (ADR 0043, 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiGlobalIndexCacheTest.kt`: `byte weighted LRU evicts the least recently used indexes (ADR 0043)`, `an index larger than the total budget is returned without being cached (ADR 0043)`, `activating a new provider version clears every cached index (ADR 0043)`, `concurrent callers share one load for the same version and key (ADR 0043)`, `different versions never share an in flight load (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiMediaUrlResolverTest.kt`: `thumbnail urls follow the hash path and lead with the configured shard (ADR 0043)`, `refreshes a stale failed version and reuses the newer configuration (ADR 0043)`, `refreshes only once when provider returns the same failed version (ADR 0043)`, `cancellation escapes refresh and leaves the version eligible to retry (ADR 0043)`, `non cancellation refresh failure consumes the failed version retry (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiNozomiReaderTest.kt`: `a whole index takes a 206 only from byte zero, complete and within its limit, and a missing one is empty (ADR 0043)`, `a page of the primary index takes a 206 only from the requested offset, and a missing one is empty (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiPageTokenCodecTest.kt`: `a token of another format version is rejected (ADR 0043, 0054)`, `the token reader ignores unknown keys but not lenient syntax, and the writer leaves out nulls (ADR 0043)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiRandomSnapshotCacheTest.kt`: `initial walks reuse only fresh snapshots while continuations retain exact snapshots (ADR 0043)`, `the load stops when every caller leaves and nothing is retained (ADR 0043)`, `byte budget evicts LRU snapshots and skips oversize snapshots (ADR 0043)`
- Left to prose: the recheck after a miss closes a race no test can force without a seam (the random cache's recheck is tested); the default hydration concurrency equalling the page size is a performance choice; page tokens never reaching saved data and the metadata parser staying free of transport hold by structure, and a text rule would miss a renamed field or a fully qualified call; the ICU-safe script extraction can only be shown on Android; a whole index starting at byte zero is enforced together with completeness, since a later start can never cover the declared body; `hitomiPageTokenJson` being local follows from the module graph; `explicitNulls = false` changes nothing today because every token field has a default.
