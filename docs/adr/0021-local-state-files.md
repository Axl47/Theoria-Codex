# 0021. Small local state files reject newer schemas, skip unreadable entries and keep lists as raw JSON

Status: Accepted

## Context

Several device-local stores (reading positions, gallery reading modes, custom sites, For You verdicts, search first page, caches) are written by builds of different versions, and one bad entry or newer file must not destroy data.

## Decision

- The reading-position and gallery-reading-mode files keep their list as raw JSON, so the schema version is checked before the list's shape is read: any version other than 1 fails the read rather than being read as, or rewritten to, the current one. One unreadable entry is skipped, including an unknown source or section, and gallery-reading-mode entries for extension sources.
- Custom sites refuse a newer schema and set a corrupt file aside (library data is unaffected). Records that decode but fail validation are written back with the rest, as typed records, so keys this version does not know are dropped; null entries are skipped on rewrite.
- Reading positions are written compact and behind (ADR 0215) because the Viewer reports every media change; a repeat of the newest position within 5 seconds keeps the stored timestamp, since both Viewer callbacks report one swipe. Writes run off the main thread.
- The search first-page file and For You's kept deck (ADR 0214) are disposable, live in the cache directory and keep live listing data (comment hint and hashes) so restored cards match the refresh; a missing file, a different version or key, or a file that is not valid JSON discards the whole cached page and only costs the instant paint, while a cached post that cannot be read (an unknown post version or source) is dropped and the rest of the page is kept, because the instant paint shows what it can and the network refresh replaces the page anyway.
- The For You verdict log keeps 20,000 rows per profile (about 5 MB), is local and outside backups, and goes with its library.
- The file-backed media cache is reconciled with one directory scan on first use, and observers never see placeholder counts. A cache file is copied before old variants are removed, and a named cover may replace a different post's bytes.

## Consequences

A schema bump needs a decoder that still rejects newer versions. Keep per-entry skipping when adding fields.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ReadingPositionRepository.kt`: `READING_POSITION_COALESCE_MS`, `decodeReadingPositions`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/GalleryReadingModeRepository.kt`: `decodeGalleryReadingModes`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/DataStoreSiteProfileRepository.kt`: `DataStoreSiteProfileRepository`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/FirstPageStore.kt`: `FirstPageStore`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ForYouVerdictRepository.kt`: `FOR_YOU_VERDICT_LIMIT`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedCacheRepository.kt`: `ensureReconciled`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `SettingsStoreRecord`, `FavoriteTagEntryRecord`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionRepositoryTest.kt`: `a missing schema reads as version 1 and a newer schema fails without touching the file (ADR 0021)`, `malformed entries do not discard valid positions (ADR 0021, 0033)`, `one swipe reported twice writes once and keeps the first timestamp (ADR 0021)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/GalleryReadingModeRepositoryTest.kt`: `unreadable and extension-source entries are skipped and a newer schema fails without touching the file (ADR 0021)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreSiteProfileRepositoryTest.kt`: `a newer schema is refused rather than overwritten (ADR 0021)`, `an unreadable file is set aside and reported, also after a restart (ADR 0021)`, `unreadable records are skipped but written back untouched (ADR 0021, 0031)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FirstPageStoreTest.kt`: `a saved page comes back for its own search only (ADR 0021)`, `comment hints survive a restore, including a listing that gave none (ADR 0021)`, `file and picture hashes survive a restore (ADR 0021)`, `an unreadable file only costs the instant paint (ADR 0021)`, `one unreadable post leaves the rest of the cached page intact (ADR 0021)`
- Left to prose: compact encoding, writing off the main thread and the media cache's single scan are performance intents with no host seam to count them; the verdict log stays out of backups because neither `BackupLibrary` nor the backup codec has a verdict field, and its 20,000-row cap is a tuning value; that the cache files live in the cache directory is app wiring.
