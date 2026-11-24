# 0009. Persisted identifiers, hashes and legacy fields keep their original form

Status: Accepted

## Context

Source ids, query hashes and post records are stored in Room, JSON stores, backups and credentials. Changing their encoding would orphan saved pages, tags or sites.

## Decision

- `SourceKey.id` is the persisted form and never changes for a source. `toString` returns it as the enum did; `parse` accepts a well-formed custom id even without a profile so data from a removed site is not dropped; `parseOrThrow` is for validated documents. `SourceKey` stays a value class over a string, so reflective Gson output (offline manifests) is unchanged from when sources were an enum. Built-ins keep their historical declaration order (`builtIns`, which `compareTo` follows), then custom sources by id, so sorted output matches the old enum ordering. `SourceKey.of(profile)` trusts the profile's stored id.
- `QueryHash` adds the media part only when set, so queries stored before the field keep their hash; the `v3` hash format must not change.
- `Query.minScore` is a retired filter kept so stored queries still decode and hash; sources ignore it. `includeTerms` is derived during construction so `copy` cannot change it independently of the groups.
- `withInferredGalleryLength` sets the unknown-length flag on nhentai/Hitomi records and Codex shares that predate it.

## Consequences

Removing a legacy field needs a migration of stored queries and a new hash version.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/SourceKey.kt`: `SourceKey`, `parse`, `parseOrThrow`, `of`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/query/QueryHash.kt`: `QueryHash`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt`: `Query.minScore`, `includeTerms`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`: `withInferredGalleryLength`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/query/QueryHashTest.kt`: stored-hash test

## Guarded by

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/SourceKey.kt`: `@JvmInline value class SourceKey private constructor(val id: String) : Comparable<SourceKey>`
