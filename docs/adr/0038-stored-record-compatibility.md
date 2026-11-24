# 0038. Durable records are separate from domain models and keep decoding every shape this app ever stored

Status: Accepted

## Context

Posts, queries and recent searches are persisted in Room payloads, offline manifests, backups and pinned-search files, and older and newer builds meet each other's data.

## Decision

- Stores serialize `PostStorageRecord`, `QueryStorageRecord` and `RecentSearchPayloadRecord`, not the domain models, so the domain can evolve without silently changing the schema.
- A null schema version means the original pre-versioned shape; unknown explicit versions fail closed. Current writers always write the explicit version.
- Newer optional fields (`galleryLengthUnknown`, `contentHash`, query `media`) are written only when set, so records without them keep their exact stored form.
- Rows written before payloads carried their own version hold a bare `Query`; `RecentSearchPayloadCodec.decode` still reads them.
- The retired For You search kind (`FYP`) decodes as a Unified search that keeps an entry of tags for every source it searched, even an empty one, so the saved-search library can convert it instead of failing.
- `RecentSearchPayloadCodec.normalize` returns an entry as storing and reading it back would: sources de-duplicated, source tags kept only for participating sources, the whole search validated.
- `withLegacyEquivalentCopies` applies to stored images with no fallback field: before `ImageRef.fallbackUrls` existed, nhentai and Hitomi stored equivalent copies (mirrors, other formats, other shards) in `progressiveUrls`, which would otherwise read as lighter renditions.
- Enum-like settings values are stored as strings, so a value this build does not know falls back instead of failing the whole file.
- A saved favorite tag keeps its first tag in `tag`, so builds that predate "or" groups still read it. `tags` is written only for a group with more than one tag and holds every alternative; `name` is a group's optional label.
- A pinned-searches file from a newer build throws `UnsupportedStoreSchemaException` and is left in place; any invalid pin fails the whole file so the file store quarantines it rather than failing startup.

## Consequences

Adding a field means making it optional and omitted when unset. Removing the legacy shapes requires migrating or dropping the stored data they cover.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/PostStorageRecords.kt`: `PostStorageRecord`, `PostStorageCodec`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/RecentsStorage.kt`: `QueryStorageRecord`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/RecentSearchPayloadCodec.kt`: `RecentSearchPayloadCodec`, `RETIRED_FOR_YOU_KIND`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/LegacyImageRefCopies.kt`: `withLegacyEquivalentCopies`
- `core-data/src/main/kotlin/com/theoriacodex/data/storage/SavedSearchSnapshot.kt`: `SavedSearchSnapshotSerializer`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `SettingsStoreRecord`, `FavoriteTagEntryRecord`
