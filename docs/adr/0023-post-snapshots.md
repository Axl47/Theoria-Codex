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
