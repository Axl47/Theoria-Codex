# 0042. The Gelbooru adapter tolerates per-fork API differences and latches detected quirks

Status: Accepted

## Context

Gelbooru 0.2 forks, gelbooru.com and rule34.xxx disagree on comments, profiles, tag lists, sample flags, hash fields and refusal formats. The adapter is configured by plain data (`GelbooruSite`) and discovers fork behaviour at runtime.

## Decision

- `GelbooruSite` is plain data so user-added sites are described like presets. Post and tag lists may be a bare array or wrapped in `{"post": [...]}` / `{"tag": [...]}`; the adapter accepts both. Defaults follow what most 0.2 sites accept.
- The app finds creator and related-post support with `is` checks, so `GelbooruSourceAdapter` returns an implementation of those interfaces only when the profile enables them.
- Comments: the DAPI answers comment XML only (`json=1` is ignored) and has no paging. When it answers anything else, a recognised key-failure body throws `AUTH_REQUIRED` and leaves the latch unset; any other non-XML answer (gelbooru.com: "Disabled due to abuse.") makes `commentsApiDisabled` latch for the adapter's lifetime and comments are read from the post page, paged by `pid` offsets; those tokens are never DAPI tokens. The key-failure body scan is skipped for comment pages because comment text could mimic a key failure. Two post-page layouts are parsed (gelbooru.com and Safebooru), and a profile id is only available where the author link has `id=` (Safebooru carries `uname`).
- Tag list: `GelbooruTagList.DETECT` sites (Safebooru) may answer XML whatever `json=1` says; the first XML answer latches `tagListIgnoresJson`, switching suggestions and counts to `autocomplete.php` and disabling trending.
- Public profiles exist on gelbooru.com but 0.2 forks send guests to login (`publicProfiles`). `fav:<account id>` was verified live on gelbooru.com with a key on 2026-11-11 (`favoritesList`). `commentAnchors` is true for forks and false for gelbooru.com. A failed profile-owner lookup throws so callers can tell it from a missing name.
- Related-post batch lookups resolve ids concurrently, each failure staying its own result. Posts that resolve survive individual failures; only when none resolved and something failed is the first failure rethrown.
- Gelbooru answers a plain-text depth refusal ("Too deep! ...") although more posts exist; it is thrown as an `UNKNOWN` failure, never read as an empty last page. Blank bodies mean no records; a bare-word reply is rejected rather than ending paging.
- Post fields: forks reporting `sample: false` may still fill `sample_url`; `created_at` is a date string on most sites with `change` as epoch seconds; the hash is `md5` on gelbooru.com and `hash` on forks.
- Newest-first adds the site's newest tag only when other tags exist: with none the site already lists newest first and repeating costs about 7 s per page. A null OR group makes the orchestrator expand groups; a null newest tag relies on the site's default id order. One `animated` tag matches the video family on these sites (checked live 2026-11).

## Consequences

New fork quirks should be added as site fields or latched detections, not adapter branches by host. Latches never reset, so a site that later changes behaviour needs an app restart.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSite.kt`: `GelbooruSite`, `GelbooruTagList`, `GelbooruOrGroup`, `RULE34XXX`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`: `GelbooruSourceAdapter`, `commentsApiDisabled`, `tagListIgnoresJson`, `throwIfAuthFailure`, `resolvePosts`, `postsOrThrow`, `parseItems`, `parsePost`, `compileTags`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruComments.kt`: `parseGelbooruCommentsXml`, `parseGelbooruCommentsHtml`, `gelbooruCommentHint`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruAccounts.kt`: `fetchGelbooruProfileOwner`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/FamilyAdapterTest.kt`: `an XML-only tag list switches suggestions and counts to autocomplete once (ADR 0040, 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruAccountsTest.kt`: `feeds follow the profile shape and the site (ADR 0008, 0042)`, `a site without public profiles has no details and makes no request (ADR 0042)`, `favorites are the fav search by account id, paged like any search (ADR 0042)`, `the profile owner lookup names the account and fails loudly on a refused page (ADR 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruCommentsTest.kt`: `a disabled DAPI falls back to the post page, remembers that, and pages by pid (ADR 0042)`, `Safebooru's column layout is read too (ADR 0042, 0054)`, `native comment anchors follow the site's markup (ADR 0008, 0042)`, `comment text that mentions keys is not mistaken for a key failure (ADR 0042)`, `a key failure does not switch the DAPI off for later comment reads (ADR 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruCustomProfileTest.kt`: `a custom profile reads safebooru shaped posts and autocomplete (ADR 0042)`, `a custom profile loads a generated sample before the original and skips one the fork disowns (ADR 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruRelatedPostsSourceAdapterTest.kt`: `related posts survive single failures and rethrow the first failure only when none resolve (ADR 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`: `a well-formed page past the last full page ends paging (ADR 0042)`, `a malformed page fails instead of ending paging (ADR 0042)`, `a depth refusal fails without ending paging (ADR 0042)`, `search accepts direct arrays and ignores malformed optional values (ADR 0042)`, `creator search uses uploader token and leaves regular search unchanged (ADR 0042)`, `rule34 search attaches credentials and maps canonical post url (ADR 0042)`, `rule34 exposes neither creator nor related posts (ADR 0042)`
- Left to prose: one `animated` tag matching the video family and `fav:` searches were verified live; new fork quirks belonging in site fields or latches is guidance for future edits; latches never resetting shows only across a restart.
