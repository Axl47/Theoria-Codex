# 0048. Provider media loads over HTTPS and prefers small renditions for cards

Status: Accepted

## Context

Provider media URLs arrive protocol-relative or as `http://`, sometimes on hosts with broken certificates, and originals can be far larger than a card needs.

## Decision

- `httpsMediaUrl` rewrites protocol-relative and `http://` URLs to HTTPS and never fetches in cleartext. Gelbooru's numbered video CDN hosts serve invalid certificates, so `normalizeGelbooruMediaUrl` rewrites them to `https://gelbooru.com/`, which serves the same paths.
- Direct Pixiv image addresses only load with a Pixiv referer, so `originUrlOrNull` uses a known Pixiv id's artwork page instead.
- Danbooru cards use the 720x720 variant (`CARD_PREVIEW_VARIANT`); the 180px default blurs in two-column cards.
- Iwara cards use the small thumbnail rendition: a custom thumbnail original is 1-7 MB, the rendition well under 100 KB.
- Cleartext is refused app-wide: the app's `network_security_config.xml` refuses it in its `base-config`, every module's shipped manifests keep that config applied and never set `usesCleartextTraffic`, and `cleartextTrafficPermitted` is written only as `true` or `false`. Only loopback (`127.0.0.1`, `localhost`) may use HTTP, because some anime extensions serve their streams from a proxy of their own on the device and device and acceptance tests serve fixture bytes there. Test source sets do not ship and are not held to this.
- nhentai prefers API-provided thumbnail paths: about half of real thumbnails use irregular names, so guessed names are a fallback.

## Consequences

Cards load fast and safely; a provider that only serves HTTP media would need an explicit exception.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceAdapterCommon.kt`: `httpsMediaUrl`, `originUrlOrNull`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`: `normalizeGelbooruMediaUrl`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapter.kt`: `CARD_PREVIEW_VARIANT`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/iwara/IwaraSourceAdapter.kt`: `iwaraAssetUrl`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiGalleryParser.kt`: thumbnail URL selection
- `app/src/main/res/xml/network_security_config.xml`: `base-config`, loopback `domain-config`
- `app/src/main/AndroidManifest.xml`: `android:networkSecurityConfig`
- `media-kit/src/main/AndroidManifest.xml`, `baseline-profile/src/main/AndroidManifest.xml`, `macrobenchmark/src/main/AndroidManifest.xml`: every other shipped manifest, and any added in another module or source set

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/common/OriginUrlTest.kt`: `Pixiv image addresses and a known Pixiv ID become the artwork page (ADR 0048)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapterTest.kt`: `post maps Danbooru timestamps artists samples and durations (ADR 0048)`, `a card uses the 720 px variant rather than the 180 px default (ADR 0048)`, `provider media addresses are upgraded to HTTPS, plain or protocol-relative (ADR 0048)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`: `search canonicalizes numbered video cdn urls with invalid host certificates (ADR 0048)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/iwara/IwaraSourceAdapterTest.kt`: `a custom thumbnail card loads the small rendition, not the uploaded original (ADR 0048)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiResolutionSourceAdapterTest.kt`: `resolve post maps v2 gallery details with page paths (ADR 0048)`
- `scripts/tests/test_check_architecture.py`: `test_reports_cleartext_beyond_loopback_in_any_source_set_adr_0048`
- Left to prose: the size and sharpness reasons behind the 720 px variant and Iwara's rendition, and how often nhentai names thumbnails irregularly, are measurements of live providers; nhentai passes an absolute `http://` path through unchanged, and the network security rule is what keeps it from loading.
