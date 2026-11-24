# 0044. nhentai falls back to mirrors for metadata but never for comments

Status: Accepted

## Context

nhentai's API often answers with a Cloudflare challenge. Mirrors can supply search, reader and tag metadata, but not comments, and a blocked request returns an HTML page.

## Decision

- `NhentaiMirror` serves search and reader pages through the Jina text mirror, and tags from the first HTML metadata mirror that lists them, with requests taking turns on the adapter's pacer.
- Comments exist only on the direct API: a blocked request fails instead of falling back. Commenters' profile pages are site accounts, not searchable tags, so they have no uploads query.
- A blocked request answers with HTML, so `jsonBody` refuses an HTML page. `ensureNhentaiOk` treats 401 and 403 as non-credential problems.
- A listing with no pages, cover or page count is still a gallery (`galleryLengthUnknown`); its pages arrive with the lookup.

## Consequences

Comments fail visibly while challenged. Adding comments to a mirror would require a mirror that carries them.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiMirror.kt`: `NhentaiMirror`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt`: `comments`, `jsonBody`, `ensureNhentaiOk`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiComments.kt`: `parseNhentaiComment`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiGalleryParser.kt`: `galleryLengthUnknown`
