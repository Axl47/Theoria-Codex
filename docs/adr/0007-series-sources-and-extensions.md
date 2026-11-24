# 0007. Series come from Mihon and Aniyomi extensions installed into app storage, trusted by pinned signing key

Status: Accepted

## Context

Extensions run third-party code. The app installs them into its own storage so no other app can replace them, and trusts them only by signing certificate. Their addresses expire and their per-series state (memos) comes from other apps' formats.

## Decision

- Trust: `ExtensionRepo.signingKey` is pinned when the repo is added; only APKs signed by it are installed, and `install` checks package, signing key and that the copy is not older than the one on the device. `restore` keeps recorded repos without a network read, because the key is what is trusted. Adding a repo installs and turns on nothing. Installing an extension that has no installed copy (other than a loaded-from-file one) turns that extension on, trusted under the repo's key; installing over an installed copy (an update) leaves its choice as it was; uninstalling removes the copy and turns it off (`ExtensionCatalog`). `ExtensionChoices.trusted` maps extension to the certificate fingerprint it had when turned on; an extension runs only while it has that certificate (`trusts`), and one with no readable signer can never be turned on. `setOn` refuses when the signer differs from the one the user was shown.
- Extension choices live where Android neither backs them up nor transfers them.
- Listing an extension that is off reads only package-manager data and runs none of its code; language lists stay empty until it is on. `ExtensionChoices.owners` records which extension offered each source so lookups load only that one.
- Defaults: `defaultLanguages` matches by language part (`pt-BR` matches `pt-PT`), else English, else the first offered.
- Ids: `custom_mihon_` and `custom_aniyomi_` prefixes follow the custom-site id grammar; an extension's id is prefix plus number and nothing else, which no site host can be, and each kind has its own prefix so numbers never collide. Mihon and Aniyomi backups resolve to the same ids. `SourceKey.seriesKind` is read from the id alone, so it survives extension removal.
- Series data: `extensionSeriesStatus` follows Mihon's numbering plus Aniyomi's `UPCOMING`. Memos are stored as given and handed back to the source; longer than `MAX_MEMO_LENGTH` (8192, loaded with every series and entry row) is dropped. `SeriesEntries.seriesMemo`: null keeps the stored memo, an empty object clears it. `entries` receives the stored list because Mihon extensions use it to stop paging.
- Media: entry media addresses and `MediaStream`s are never stored (they can expire within minutes) and are asked for on every open; a caller that handles only one kind fails on the other. Stream labels (`uniqueLabels`, `playable`) are trimmed, unique and non-blank because the Viewer remembers a choice by label; the first stream is the default. Hosts fill a stream's headers from its source when it has none.
- Covers go through `SeriesCovers` at load time so the stored cover remains what the source gave.
- `UnsupportedEntriesException.message` is shown to the user as is; any other failure's text is never shown.
- `withChoices` keeps a chosen filter only when place, kind and name still line up, so stale choices never fail a search; `placeOf` re-finds options by label after reordering.
- `seriesReadingMode` guesses long-strip from stored genres and language (Korean and Chinese comics are long strips), so no extension loads to open a chapter.

## Consequences

Updates signed by the same key stay on; a key change silently turns an extension off until the user re-approves. Supporting multi-season anime requires lifting `UnsupportedEntriesException`.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/ExtensionRepos.kt`: `ExtensionRepo`, `ExtensionRepos`, `storedRepoAddress`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCatalog.kt`: `ExtensionCatalog`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/Extensions.kt`: `ExtensionChoices`, `trusts`, `SeriesExtensions`, `defaultLanguages`, `InstalledExtension`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/Series.kt`: `MAX_MEMO_LENGTH`, `SeriesEntries`, `SeriesSource`, `SeriesCovers`, `UnsupportedEntriesException`, `extensionSeriesStatus`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/SeriesFilter.kt`: `withChoices`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/SeriesKind.kt`: `MIHON_SOURCE_ID_PREFIX`, `ANIYOMI_SOURCE_ID_PREFIX`, `seriesKind`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/SeriesReadingMode.kt`: `seriesReadingMode`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`: `MediaStream`, `playable`, `uniqueLabels`
