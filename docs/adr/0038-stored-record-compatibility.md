# 0038. Durable records are separate from domain models and keep decoding every shape this app ever stored

Status: Accepted

## Context

Posts, queries and recent searches are persisted in Room payloads, offline manifests, backups and pinned-search files, and older and newer builds meet each other's data.

## Decision

- Stores serialize `PostStorageRecord`, `QueryStorageRecord` and `RecentSearchPayloadRecord`, not the domain models, so the domain can evolve without silently changing the schema.
- A null schema version means the original pre-versioned shape; unknown explicit versions fail closed. Current writers always write the explicit version.
- Newer optional fields (`galleryLengthUnknown`, `contentHash`, query `media`, query `dateLastDays`) are written only when set, so records without them keep their exact stored form. An older build ignores a field it does not know, so it reads a preset search (`dateLastDays`) as having no date filter; that beats failing the whole record.
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

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/storage/PostStorageRecordsTest.kt`: `versioned storage record round trips every supported Post field (ADR 0038)`, `pre-versioned storage record retains safe typed fallbacks (ADR 0038)`, `unknown explicit storage version fails closed (ADR 0038)`, `a gallery of unknown length keeps its flag, and other records stay as they were (ADR 0006, 0038)`, `nhentai and hitomi records saved before fallback urls keep their copies as fallbacks (ADR 0038)`, `an explicit fallback field is never reinterpreted (ADR 0038)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomCodexLikesRepositoryTest.kt`: `unsupported and malformed local Post payloads fail explicitly (ADR 0016, 0038)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomPayloadGoldenTest.kt`: `upserting an unchanged post keeps the Gson-written row (ADR 0016, 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/QueryStoreGoldenTest.kt`: `a search without a media filter is stored without a media key (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/RecentSearchPayloadGoldenTest.kt`: `a bare Query row infers its source metadata (ADR 0038)`, `a preset is stored as its days and loads back relative, and stored dates stay fixed (ADR 0038, 0084)`, `a Gson-written For You row reads as Unified and keeps its tags by source (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/RecentSearchPayloadCodecTest.kt`: `a retired For You payload reads as Unified with tags for every source it searched (ADR 0038)`, `normalize gives the entry storing would keep, sources once and tags only for searched sources, and refuses what storing refuses (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedQueryRepositoryTest.kt`: `query repository falls back when persisted source or sort is unknown (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `settings values this build does not know fall back without resetting the file (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FavoriteTagEntryRecordTest.kt`: `a saved group keeps its first tag readable by older builds and round trips (ADR 0038)`, `a record written before or groups reads as one saved tag (ADR 0038)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedSavedSearchRepositoryTest.kt`: `a file from a newer build is refused and left in place (ADR 0038)`, `semantic corruption is quarantined before recovering an empty library (ADR 0038)`
- Left to prose: stores serializing storage records rather than domain models holds because `:core-domain` has no serialization plugin, so a domain model cannot be encoded; adding a field as optional and omitted when unset, and migrating before dropping a legacy shape, are rules for authors.
