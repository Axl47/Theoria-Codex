# 0046. Pixiv credential refreshes are serialized and fenced by a session revision

Status: Accepted

## Context

Pixiv OAuth tokens rotate on refresh, so two concurrent refreshes or a refresh racing an account change could overwrite newer credentials or resurrect a removed session.

## Decision

- `SessionBoundSourceCredentials` serializes account decisions and refresh commits with a mutex, but never holds it during provider I/O. Resetting encrypted storage is also an account decision and bumps the revision even if it fails to persist.
- `PixivCredentialSession` (tokens plus `revision`) is process-local authority for a refresh; credentials keep their existing wire format. `commitPixivRefresh` commits a refresh against the session it started from; read-only providers reject refresh writes by default and mutable owners must commit atomically.
- `PixivAuthApi.refresh` does no locking: callers serialize through `PixivTokenCoordinator`, which holds one lock across read, refresh and commit.
- Parsed tags keep each translation right after its native tag (`canonical`) because Codex automatic tags and the clipboard match English there; `taxonomy` pairs them so chips and learning see one tag.
- Two `RequestPacer` lanes: `browsePacer` for feed pages and settings lookups, `durationPacer` for ugoira duration lookups. The duration engine already paces per source; the separate lane keeps background lookups from queueing in front of feeds.
- Public bookmarks page by `max_bookmark_id`, so their page token is the previous response's absolute `next_url`, requested as given. Comment continuations use the same absolute-`next_url` token; creator uploads instead page by numeric offset parsed from `next_url`.
- The bearer token only goes to Pixiv's own API, so a stored `next_url` must start with `PIXIV_API_BASE/` (`trustedPixivLink`).
- With a comments page token, the next page is requested from its `next_url` and carries no total. Without one, a `parent` selects its first replies page (no total); otherwise the first top-level page also reads the comment total, and a failed total lookup leaves it null instead of failing the page.
- `authorizedGet` sends one authorized GET; on 401 or 403 it asks the coordinator for tokens (`refreshAfterAuthFailure`), which reuses tokens another caller already refreshed or else refreshes under the lock, and retries the GET once. A second failure is reported.
- Translations are always requested in English so they line up with the booru vocabulary.
- `resolveDurationMetadata` lets failures propagate so the engine can retry later.
- `PixivUgoiraMetadataCache` is one process-wide instance shared by the duration lookup and the ugoira player, so a card whose metadata is already cached does not ask the rate-limited endpoint again. Completed responses are reused; concurrent callers that both miss can each make a request.
- The ugoira search term (`うごイラ`) finds only most ugoira, though nearly every match is one; checked live 2026-11.

## Consequences

Callers must use the coordinator, never `PixivAuthApi` directly. Changing the token format means revisiting the revision fence.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/credentials/SessionBoundSourceCredentials.kt`: `SessionBoundSourceCredentials`, `invalidatePixivSession`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/credentials/SourceCredentialsProvider.kt`: `PixivCredentialSession`, `commitPixivRefresh`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivAuthApi.kt`: `refresh`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivIllustParser.kt`: `ParsedPixivTags`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt`: `browsePacer`, `durationPacer`, `publicBookmarks`, `trustedPixivLink`, `comments`, `authorizedHeaders`, `resolveDurationMetadata`, `animatedTerm`, `authorizedGet`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivUgoiraMetadataCache.kt`: `PixivUgoiraMetadataCache`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivAccountDecisionTest.kt`: `disconnect new login and reusing identical credentials all reject the older refresh (ADR 0046)`, `expired response cannot clear a newer login (ADR 0046)`, `a reset that fails to persist still rejects the older refresh (ADR 0046)`, `saving the same tokens again still rejects a refresh that started before it (ADR 0046)`, `an account decision and a refresh commit never run inside each other (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivAccountsTest.kt`: `favorites are public bookmarks and continue through next_url (ADR 0046)`, `a next page link away from the Pixiv API is refused before the token is sent (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivCommentsTest.kt`: `the first page maps comments and stamps and takes its total from the illustration (ADR 0046)`, `a failed total lookup leaves the total unknown instead of failing the page (ADR 0046)`, `a page token is requested as given (ADR 0046)`, `replies are requested for the parent comment (ADR 0046)`, `a next link outside Pixiv's API is never followed with the token (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivRequestPacingTest.kt`: `background duration lookups do not delay the next browse request (ADR 0046)`, `duration lookups reuse ugoira metadata another consumer already fetched (ADR 0046)`, `a completed duration lookup is kept for the player to reuse (ADR 0046)`, `a failed duration lookup propagates its failure so the engine can retry (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapterTest.kt`: `an authorization refusal refreshes once and retries once, and a second refusal is reported (ADR 0046)`, `creator search uses user illustrations endpoint and preserves pagination (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivTagsAdapterTest.kt`: `autocomplete and trending preserve native tags with translated labels (ADR 0046)`, `search pairs each native tag with its English translation (ADR 0046)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/pixiv/PixivTokenCoordinatorTest.kt`: `auth failure reuses token persisted by concurrent winner (ADR 0046)`, `a provider that cannot commit refreshes fails the refresh instead of using its tokens (ADR 0046)`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivAuthApi.kt`: `internal suspend fun refresh(refreshToken: String): PixivAuthTokens`
- Left to prose: the mutex never being held across provider I/O would show only as the decision tests hanging; `うごイラ` finding most ugoira is live behaviour; two callers that both miss the ugoira cache each making a request is allowed, not enforced; the process-wide ugoira cache being shared by the duration lookup and the player is unguarded, because the app container does the sharing and no core-sources test can see it.
