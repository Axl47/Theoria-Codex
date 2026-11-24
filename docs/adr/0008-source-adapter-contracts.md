# 0008. Source capabilities are optional adapter interfaces with throw-on-failure and null-for-absent results

Status: Accepted

## Context

Sources differ in what they can answer: some have an MD5 search term, some expose accounts, some need provider URL rules to recover expired media. The app layer must not learn provider rules, and callers must tell "the post is gone" from "the request failed".

## Decision

- Each optional capability is its own interface (`SearchMetadataSourceAdapter`, `AccountProfileSourceAdapter`, `FileHashLookupSourceAdapter`, `RelatedPostsSourceAdapter`, `CommentsSourceAdapter`, `MediaRecoverySourceAdapter`, `DurationMetadataSourceAdapter`); a source leaves out what it cannot do.
- Acquisition failures throw; null is a successful answer whose meaning depends on the lookup: `resolveSearchMetadata` returns null when the post no longer exists, `findByFileHash` when the site has no post with that MD5, `accountDetails` when the source has nothing beyond what the creator profile already holds (the account exists), and `nativeCommentAnchor` when the site has no comment fragment of its own.
- `AccountProfileSourceAdapter`: a source whose creators can be accounts or tags decides from the profile's shape alone, so the page layout is known before any request.
- `FileHashLookupSourceAdapter` is implemented only by families with an `md5:` search (Danbooru and Gelbooru families, custom sites included).
- `CommentsSourceAdapter.nativeCommentAnchor` returns the site's own comment fragment so shared links also jump in a browser; null falls back to Theoria's fragment.
- `MediaRecoverySourceAdapter` receives the original post and the failed media reference, so provider URL rules stay in the source.
- `fetchTagCountsIncrementally` publishes completed batches before returning so callers keep them if later work times out; `onCounts` may run on any thread but never concurrently with itself.
- `SourceCapabilities.requiresExactTags`: the provider matches only tags spelled as it lists them, so Search adds a typed tag only after autocomplete or history confirms it, and Unified looks up the provider's spelling first.
- `SourceCapabilities.animatedTerm`: the term is added as one more required tag; `overLimit` replaces it when the term limit is full and costs no term (Danbooru's `filetype:`). The phone-side animated filter runs either way.

## Consequences

New sources opt into capabilities without touching shared code. Callers must handle both null and thrown failure on every lookup, and read null by that lookup's meaning. Moving a capability into the base interface would force every source to stub it.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`: `SearchMetadataSourceAdapter`, `AccountProfileSourceAdapter`, `FileHashLookupSourceAdapter`, `CommentsSourceAdapter`, `MediaRecoverySourceAdapter`, `SourceCapabilities`, `AnimatedSearchTerm`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/adapter/SourceAdapterContractTest.kt`: `the base adapter inherits and shares nothing with a capability interface (ADR 0008)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`: `suspend fun resolveSearchMetadata(post: Post): Post?`, `suspend fun findByFileHash(md5: String): Post?`, `suspend fun accountDetails(creator: CreatorProfile): AccountDetails?`, `fun nativeCommentAnchor(commentId: String): String? = null`, `fun accountFeeds(creator: CreatorProfile): List<AccountFeed>`, `suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post?`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/orchestration/SearchMetadataHydrationTest.kt`: `metadata failures remain failures instead of becoming empty successful results (ADR 0008, 0011)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/RealAdapterRegistryTest.kt`: `only the families with an md5 search look up files by hash (ADR 0008)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruAccountsTest.kt`: `feeds follow the profile shape and the site (ADR 0008)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruAccountsTest.kt`: `only user creators are accounts, told apart by the profile alone (ADR 0008)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruCommentsTest.kt`: `native comment anchors follow the site's markup (ADR 0008)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiCreatorSourceAdapterTest.kt`: `tag count lookup publishes completed counts before a later request times out (ADR 0008)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchDraftReducerTest.kt`: `gelbooru input requires an exact suggestion and canonicalizes spaces (ADR 0008)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/search/UnifiedQueryPreparationTest.kt`: `Unified looks up the provider's spelling of a typed tag before searching (ADR 0008)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/query/MediaTermsTest.kt`: `an animated search gains the source's term while it fits the limit (ADR 0008)`, `a full term limit falls back to the no-cost term (ADR 0008)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchVisibilityFiltersTest.kt`: `animated-only hides stills on the phone in every mode but an nhentai search (ADR 0008, 0080)`
- Left to prose: each source throwing rather than returning null on a failed request is checked adapter by adapter in core-sources; `onCounts` never overlapping itself holds by construction (one sequential loop calls a non-suspend callback), and a test could catch a parallel rewrite only flakily.
