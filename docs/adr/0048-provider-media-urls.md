# 0048. Provider media loads over HTTPS and prefers small renditions for cards

Status: Accepted

## Context

Provider media URLs arrive protocol-relative or as `http://`, sometimes on hosts with broken certificates, and originals can be far larger than a card needs.

## Decision

- `httpsMediaUrl` rewrites protocol-relative and `http://` URLs to HTTPS and never fetches in cleartext. Gelbooru's numbered video CDN hosts serve invalid certificates, so `normalizeGelbooruMediaUrl` rewrites them to `https://gelbooru.com/`, which serves the same paths.
- Direct Pixiv image addresses only load with a Pixiv referer, so `originUrlOrNull` uses a known Pixiv id's artwork page instead.
- Danbooru cards use the 720x720 variant (`CARD_PREVIEW_VARIANT`); the 180px default blurs in two-column cards.
- Iwara cards use the small thumbnail rendition: a custom thumbnail original is 1-7 MB, the rendition well under 100 KB.
- nhentai prefers API-provided thumbnail paths: about half of real thumbnails use irregular names, so guessed names are a fallback.

## Consequences

Cards load fast and safely; a provider that only serves HTTP media would need an explicit exception.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceAdapterCommon.kt`: `httpsMediaUrl`, `originUrlOrNull`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`: `normalizeGelbooruMediaUrl`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapter.kt`: `CARD_PREVIEW_VARIANT`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/iwara/IwaraSourceAdapter.kt`: `iwaraAssetUrl`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiGalleryParser.kt`: thumbnail URL selection
