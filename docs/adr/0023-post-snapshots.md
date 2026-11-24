# 0023. Post snapshots drop device-bound data and merging never erases known data

Status: Accepted

## Context

Posts are stored once and shared across Codices, Recents and backups, but snapshots from routes can be sparse and some fields are valid only on the device or briefly.

## Decision

- Portable snapshots carry no device paths and no stream addresses (expiring, header-bound). Only `http` and `https` URLs with a host survive, and any URL with user info, a query string or a fragment is dropped; credentials or signatures carried in a URL path are not detected. The exceptions are the canonical Gelbooru and rule34.xxx post page URLs and Gelbooru creator profile URLs, which use query parameters and are rebuilt from their numeric ids.
- `mergeSparsePost` never lets absent fields erase known media or metadata. Fields that depend on the file follow it: a content hash survives only while the snapshot still points at the same full file, and a post's stored video variants survive only while the incoming URL is absent or unchanged, so a changed URL can drop them. A taxonomy count the snapshot omits is unknown, not zero. A creator profile from another source replaces the stored one whole; the same source merges per field.
- Stable-keyed lists put incoming entries first, then stored entries the snapshot lacks.

## Consequences

Clearing a field requires an explicit replace, not a merge.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/PortablePostSnapshot.kt`: `portablePostSnapshot`, `portableMediaUrl`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/PostMergePolicy.kt`: `mergeSparsePost`, `mergeTaxonomyTerm`, `mergeCreatorProfile`, `mergeStableBy`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/PortablePostSnapshotTest.kt`: `a portable URL is plain http or https with a host and no user, query or fragment (ADR 0023)`, `a portable snapshot carries no stream addresses (ADR 0023)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileBackupServiceTest.kt`: `export strips device paths signed URLs and transient settings (ADR 0023)`, `canonical booru post links survive while arbitrary credential queries do not (ADR 0023)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/PostStorageRecordsTest.kt`: `merging a ref without a thumbnail keeps the stored one (ADR 0023)`, `merging a snapshot without a hash keeps the stored hash only for the same file (ADR 0023)`, `merging tags without counts or translations keeps the stored ones (ADR 0023)`, `merging creators without links keeps the stored links (ADR 0023)`, `stored video renditions survive a merge only while the full file's address is absent or unchanged (ADR 0023)`, `a creator from another source replaces the stored profile whole (ADR 0023)`, `a merge lists incoming entries first, then stored entries the snapshot lacks (ADR 0023)`
- Left to prose: credentials carried in a URL path are knowingly not detected; clearing a field by explicit replace is a rule for callers.
