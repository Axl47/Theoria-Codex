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

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiCommentsTest.kt`: `a page maps authors, absolute avatars and epoch milliseconds and points at the next page (ADR 0044)`, `a challenged comments request fails with the challenge instead of reading a mirror (ADR 0044)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiGalleryParserTest.kt`: `a listing with no pages, cover or page count is a gallery of unknown length (ADR 0006, 0044)`, `a listing that declares its page count knows its length (ADR 0044)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiResolutionSourceAdapterTest.kt`: `search falls back to mirrored web page when api is cloudflare blocked (ADR 0044)`, `resolve post falls back to mirrored reader page when api is cloudflare blocked (ADR 0044)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiSearchSourceAdapterTest.kt`: `search maps blocked html response to network failure (ADR 0044)`, `non-challenge forbidden response keeps nhentai unknown failure semantics (ADR 0044)`
- Left to prose: tags coming from the first metadata mirror that lists them and mirror requests taking turns on the pacer are not asserted, since the fixtures serve one mirror and no timing.
