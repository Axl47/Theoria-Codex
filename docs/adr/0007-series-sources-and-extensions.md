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

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionCatalogTest.kt`: `a repo is added with the key it names, once per address, and refused when a backup knew another key (ADR 0007)`, `nothing is installed that is not the package the repo named, signed with its key, and no older than the copy on the device (ADR 0007)`, `restoring repos stores the valid records in one update without reading them, skips present ones and refuses a bad address or key (ADR 0007)`, `an extension installed from a repo is kept privately and turned on, an update keeps the user's choice, and uninstalling turns it off (ADR 0007)`, `adding a repo installs and turns on nothing (ADR 0007)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/series/ExtensionsTest.kt`: `only a certificate the user turned on is trusted (ADR 0007)`, `the device's languages are on by default (ADR 0007)`, `English is on when no source is in a device language (ADR 0007)`, `the first source is on when there is no English one either (ADR 0007)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionRegistryTest.kt`: `an extension without a signing certificate cannot be turned on (ADR 0007)`, `a file replaced while the user was asked is not turned on (ADR 0007)`, `an extension that is off is listed without running its code (ADR 0007, 0189)`, `a lookup loads only the extension that last offered its source (ADR 0007)`
- `app/src/test/java/com/theoriacodex/app/di/DeviceExtensionChoicesTest.kt`: `extension choices live where Android neither backs them up nor transfers them (ADR 0007)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/series/SeriesKindTest.kt`: `an extension source's kind is read from its id, which parses as a custom-site id (ADR 0007)`, `sources that run no extension are not extension sources (ADR 0007)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/MihonSeriesSourceTest.kt`: `each status number Mihon and Aniyomi use has its status, and any other is unknown (ADR 0007)`, `memos the source keeps reach the app and come back when a stored series or chapter is rebuilt (ADR 0007)`, `a listing is handed the stored chapters, memos included, and the series memo it leaves comes back (ADR 0007)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/series/SeriesEntriesTest.kt`: `a listing without a series memo keeps the stored one and an empty one clears it (ADR 0007)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/Series.kt`: `sealed interface EntryMedia`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/MediaStreamTest.kt`: `playable streams have an address and a label of their own (ADR 0007)`, `a repeated label skips a count another label already holds (ADR 0007)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSourceTest.kt`: `a stream without headers gets its source's, and one with its own keeps them (ADR 0007)`, `a stored cover is tagged without loading the extension, and fetched through the source's own client with its headers (ADR 0007)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesPageViewModelTest.kt`: `a source that cannot list the entries says why in its own words without retry (ADR 0007)`, `any other failure's own text is never shown (ADR 0007)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/series/SeriesFilterTest.kt`: `a value that no longer lines up keeps the default (ADR 0007)`, `options are matched by label when the source reorders them (ADR 0007)`, `an option whose label repeats keeps its own place, and moves only to a label that does not (ADR 0007)`, `a shorter or longer list on either side keeps what lines up (ADR 0007)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/series/SeriesReadingModeTest.kt`: `a long-strip genre reads vertically (ADR 0007)`, `a Korean or Chinese source reads vertically (ADR 0007)`
- Left to prose: that entry media addresses are never stored is an absence (no stored series or entry field holds them); Mihon and Aniyomi backups resolving to the same ids, the Viewer defaulting to the first stream, and opening a chapter without loading its extension are checked in the core-data, Viewer and app batches.
