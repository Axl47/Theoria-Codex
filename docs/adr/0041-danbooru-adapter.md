# 0041. The Danbooru adapter works within guest limits and id paging

Status: Accepted

## Context

Danbooru guests are limited in search terms and page depth, its Cloudflare challenges generic agents, and several records hide data from guests.

## Decision

- Newest-first searches page by post id (`b<id>`): guests cannot open numbered pages past 1000 and new uploads would shift later pages. Score sorts cannot use id paging and keep numbered pages. Favorites also use page numbers because `ordfav:` orders by favorite time and an id cursor would skip posts.
- Guests may search two tags and an `order:` metatag counts as one (`DEFAULT_TERM_LIMIT` = 2 in `DanbooruFamily`, `rankedSortUsesTerm = true`). The site defaults to newest first, so only ranked sorts spend a term. The animated search uses `animated` and falls back to `filetype:gif,mp4,webm,zip` over the limit because `filetype:` costs no term; static GIFs it admits are left to the phone-side filter.
- Keys go out as `login` and `api_key` to the site's `baseUrl` only. Every request sends `THEORIA_SOURCE_USER_AGENT` because Danbooru's Cloudflare challenges generic browser agents.
- Posts without a file URL are restricted to signed-in users and are dropped; when a requested post exists but its files are withheld the adapter reports `AUTH_REQUIRED`, not not-found. Rejected searches carry an explanation in a JSON `message` that is surfaced.
- Ugoira originals are zip archives, so the playable `sample` webm is used as the full media.
- Only user creators are accounts (searched as `user:<name>`); artist-tag creators search the bare tag and have no account feeds. The account header counts private favorites as 0 and has no avatar or bio.

## Consequences

Guest limits are encoded in the site description; a site with different limits needs its own value (probing measures it for custom sites).

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapter.kt`: `DanbooruSite`, `DanbooruSourceAdapter`, `accountPosts`, `parsePost`, `compileTags`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruAccounts.kt`: `danbooruAccountName`, `parseDanbooruAccountDetails`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/Families.kt`: `DanbooruFamily` (term limit)

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/FamilyAdapterTest.kt`: `a custom Danbooru site sends its keys, headers and term limit (ADR 0041)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/SiteProberTest.kt`: `a Danbooru site that does not report its tag limit falls back to two (ADR 0041)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruAccountsTest.kt`: `only user creators are accounts, told apart by the profile alone (ADR 0008, 0041)`, `private favorites count as zero (ADR 0041)`, `an artist tag has no account details and asks for nothing (ADR 0041)`, `favorites page by number because ordfav is not id ordered (ADR 0041)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapterTest.kt`: `score search spends a term on its order metatag and keeps numbered pages (ADR 0041, 0054)`, `newest search sends only the typed tags and pages before the lowest post id (ADR 0041)`, `a post whose files are withheld is an account failure, not a missing post (ADR 0041)`, `ugoira plays its webm sample and posts without a file are left out (ADR 0041)`, `rejected search reports the provider's own explanation (ADR 0041)`, `guests keep two terms, a ranked sort spends one, and animated falls back to the free filetype term (ADR 0041)`
- Left to prose: the guest page limit and Cloudflare's dislike of generic agents are provider facts checked live; static GIFs the animated term admits are filtered on the phone (app-logic).
