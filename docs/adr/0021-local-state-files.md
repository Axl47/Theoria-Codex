# 0021. Small local state files reject newer schemas, skip unreadable entries and keep lists as raw JSON

Status: Accepted

## Context

Several device-local stores (reading positions, gallery reading modes, custom sites, For You verdicts, search first page, caches) are written by builds of different versions, and one bad entry or newer file must not destroy data.

## Decision

- The reading-position and gallery-reading-mode files keep their list as raw JSON, so the schema version is checked before the list's shape is read: any version other than 1 fails the read rather than being read as, or rewritten to, the current one. One unreadable entry is skipped, including an unknown source or section, and gallery-reading-mode entries for extension sources.
- Custom sites refuse a newer schema and set a corrupt file aside (library data is unaffected). Records that decode but fail validation are written back with the rest, as typed records, so keys this version does not know are dropped; null entries are skipped on rewrite.
- Reading positions are written compact because the Viewer rewrites the file on every media change; a repeat of the newest position within 5 seconds keeps the stored timestamp, since both Viewer callbacks report one swipe. Writes run off the main thread.
- The search first-page file is disposable, lives in the cache directory and keeps live listing data (comment hint and hashes) so restored cards match the refresh; a missing file, a different version or execution key, or a cached post that fails to decode discards the whole cached page and only costs the instant paint.
- The For You verdict log keeps 20,000 rows per profile (about 5 MB), is local and outside backups, and goes with its library.
- The file-backed media cache is reconciled with one directory scan on first use, and observers never see placeholder counts. A cache file is copied before old variants are removed, and a named cover may replace a different post's bytes.

## Consequences

A schema bump needs a decoder that still rejects newer versions. Keep per-entry skipping when adding fields.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadingPositionRepository.kt`: `READING_POSITION_COALESCE_MS`, `decodeReadingPositions`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/GalleryReadingModeRepository.kt`: `decodeGalleryReadingModes`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/DataStoreSiteProfileRepository.kt`: `DataStoreSiteProfileRepository`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SearchFirstPageStore.kt`: `SearchFirstPageStore`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ForYouVerdictRepository.kt`: `FOR_YOU_VERDICT_LIMIT`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedCacheRepository.kt`: `ensureReconciled`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `SettingsStoreRecord`, `FavoriteTagEntryRecord`
