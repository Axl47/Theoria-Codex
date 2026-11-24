# 0115. One catalog holds source labels, headers, grouping and logos; custom sites install into it as a whole

Status: Accepted

## Context

Source ordering, presentation and media headers were being repeated in route code. User-added sites appear and disappear while their stored data stays.

## Decision

- `SourcePresentationCatalog` is the single app-layer catalog for labels, headers, exposure, ordering, grouping and logos. Custom entries are replaced as a whole by `installCustom`, read without locking (`@Volatile`), and installed only by the custom-site runtime, which installs presentations before adapters become visible and withdraws them after.
- `presentation` falls back to a readable label for a custom id with no profile (a removed or not-yet-restored site), so stored data from such a site still renders.
- Groups with a `folderLabel` fold their sources behind one picker chip so they do not crowd it; picking a down source retries it, a challenged one opens its verification instead. Options arrive in presentation order, so folders follow their first source's position.
- `SourceImage` requests avatars and stamps at their drawn size with the source's media headers, since Pixiv's image host refuses requests without its Referer.
- Creator browsing is derived from the registered adapter interfaces (`creatorBrowsingSources`) so the UI stays honest when a provider gains or loses support, with no second hard-coded provider allowlist.

## Consequences

Adding a source touches the catalog and its adapter, not route code.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceMetadata.kt`: `SourcePresentationCatalog`, `installCustom`, `presentation`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceOperationalCapabilities.kt`: `creatorBrowsingSources`
- `media-kit/src/main/kotlin/com/theoriacodex/app/ui/components/SourceImage.kt`: `SourceImage`
- `media-kit/src/main/kotlin/com/theoriacodex/app/ui/components/SourceSelector.kt`: `SourceIconMenu`
