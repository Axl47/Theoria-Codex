# 0053. Paheal is excluded from hash lookup and infers file type from data attributes

Status: Accepted

## Context

Paheal's posts carry a file hash, but Shimmie's `hash=` search is unverified through the RSS/HTML path this adapter uses. Its URLs have no extensions and its thumbnails are always JPEG.

## Decision

- `Rule34PahealSourceAdapter` does not implement `FileHashLookupSourceAdapter`, so other sites' "Find on other sites" skips Paheal.
- A file's type comes from `data-mime` (for example `image/gif; animated=true`) or an older `data-ext`, because the URL cannot say.
- Thumbnails are labelled `PAHEAL_THUMBNAIL_MIME` (`image/jpeg`) whatever the post is.

## Consequences

Paheal can join hash lookup once `hash=` search is verified through this path.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/Rule34PahealSourceAdapter.kt`: `Rule34PahealSourceAdapter`, `pahealFileMime`, `PAHEAL_THUMBNAIL_MIME`
