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
