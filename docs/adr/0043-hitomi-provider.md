# 0043. Hitomi is read from Nozomi indexes with single-flight, version-bound caches and in-memory tokens

Status: Accepted

## Context

Hitomi's search pages are metadata shells and its media hosts, shards and base path come from a mutable `gg.js` file. The adapter reads the binary indexes directly. Several loads can race, and the provider's configuration can change between a listing and a media request.

## Decision

- The adapter reads Nozomi indexes, does typed intersections and subtractions, and hydrates only the page's IDs, one request per card (`DEFAULT_HYDRATION_CONCURRENCY`) multiplexed over one HTTP/2 connection. A missing index (404/416) is empty. Whole-index reads (`readWholeNozomi`, `readPrimaryRange`: random and secondary-membership indexes) accept a 206 only if it starts at byte zero and covers the complete declared body, and no whole index may hold more than its id limit. The paged primary reader instead accepts a 206 slice whose validated range starts at the requested offset.
- Caches (`HitomiGalleryManifestStore`, `HitomiGlobalIndexCache`, `HitomiRandomSnapshotCache`) are in-memory only, byte-budgeted, and share concurrent loads, which are cancelled only when every waiting caller has left. After a miss each rechecks the cache, because a load that overlapped the miss may have finished first. Missing or failed loads are not kept. The global index cache is bound to the active index version; a load finishing after a version change is returned but not kept. Random snapshots are reused by freshness for a first page and exactly by token for a continuation. A load timeout is distinguished from a missing gallery (null) by a `Result` wrapper.
- Page tokens live in a search's in-memory continuation, never in saved data, so only the current version decodes. `hitomiPageTokenJson` is a lenient reader local to core-sources, which cannot see core-data's `DurableJson`.
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
