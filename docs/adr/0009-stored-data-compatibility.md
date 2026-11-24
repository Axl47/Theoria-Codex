# 0009. Persisted identifiers, hashes and legacy fields keep their original form

Status: Accepted

## Context

Source ids, query hashes and post records are stored in Room, JSON stores, backups and credentials. Changing their encoding would orphan saved pages, tags or sites.

## Decision

- `SourceKey.id` is the persisted form and never changes for a source. `toString` returns it as the enum did; `parse` accepts a well-formed custom id even without a profile so data from a removed site is not dropped; `parseOrThrow` is for validated documents. `SourceKey` stays a value class over a string, so reflective Gson output (offline manifests) is unchanged from when sources were an enum. Built-ins keep their historical declaration order (`builtIns`, which `compareTo` follows), then custom sources by id, so sorted output matches the old enum ordering. `SourceKey.of(profile)` trusts the profile's stored id.
- `QueryHash` adds the media part only when set, so queries stored before the field keep their hash; the `v3` hash format must not change.
- `Query.minScore` is a retired filter kept so stored queries still decode and hash; sources ignore it, and reading it needs `@OptIn(RetiredQueryField::class)`, taken only by the query stores and the hash. `includeTerms` is derived during construction so `copy` cannot change it independently of the groups.
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

- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/SourceKeyTest.kt`: `built-in ids keep their historical names and round trip (ADR 0009)`, `built-ins match exactly while custom ids normalize (ADR 0009)`, `natural order is the old enum order followed by custom ids (ADR 0009)`, `a stored profile's id is its source identity (ADR 0009)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/SourceKey.kt`: `@JvmInline value class SourceKey private constructor(val id: String) : Comparable<SourceKey>`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/query/QueryHashTest.kt`: `media filter adds to the hash only when animated (ADR 0009)`, `a stored query with a retired minimum score keeps its hash (ADR 0009)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt`: `@RetiredQueryField val minScore: Int?,`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/FacetedDomainModelsTest.kt`: `copying a query's groups recomputes its include terms (ADR 0009)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/PostStorageRecordsTest.kt`: `nhentai and hitomi listings saved before the gallery flag read as galleries of unknown length (ADR 0009)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/CodexShareModelsTest.kt`: `a shared gallery listing with no pages reads as a gallery of unknown length (ADR 0009)`
