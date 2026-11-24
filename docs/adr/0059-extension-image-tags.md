# 0059. Extension images are fetched through the source's own client, selected by an HMAC-signed address tag

Status: Accepted

## Context

Some sites answer any client other than the extension's own with a bot check for cover requests, so covers and page images must go through the source's client (with its headers, tokens and descrambling). The app's media client also loads addresses supplied by other parties (such as a Custom Site's thumbnails), and a URL fragment is theirs to write.

## Decision

- Every address handed to the app ends in a fragment naming its source by full key, so two kinds computing the same numeric id stay apart. The interceptor on the app's media client answers tagged addresses and passes others through.
- A tag is honored only with an HMAC under the install's `key` over the address and the tag's content. A tag without the right code is an ordinary request without the fragment; no extension is loaded or asked, and the code is checked before anything loads. The code covers the address, so a leaked tag cannot tag another address. Forged or stale tags are logged by host only, once.
- The key is random, made once per install and stored under `noBackupFilesDir` (neither backed up nor transferred); if unreadable it is replaced. A stored cover is re-signed with the current key each time its address is read (`address`), so covers need no reopening; URLs already issued under the old key load as ordinary requests. It is read lazily, best warmed off the main thread.
- A stored cover is tagged from its series' source key without loading anything; the extension loads when the image is first requested. Requests that may only read caches never reach the source.
- Only sources last passed to `publish` answer: extension loaded completely, extension and language on. The table is replaced at once and read without locks. A source `load` did not publish is not asked again until the next publish. A source is recorded as unanswered only after `load` returns; requests that arrive while a load runs each call `load`, and the registry's mutex serialises them, so a chapter's pages asking together wait for the load under way.
- The fragment prefix stays `mihon=` for every kind because covers stored before 2026-11-22 carry it and `FRAGMENT` finds those to replace.
- Mihon pages whose source has not named an image are handed out as the naming page's address, tagged with place and chapter, so pages never share an address. The image address is asked for at request time and again after a failed fetch (they expire), dropping only the address that fetch used. Pages are held under one lock each, in a bounded map; once evicted, the chapter's pages are listed again and the same index taken. Pages asking together share one listing per chapter (`listings`), so a site is not asked once per page; a failed listing fails every page waiting on it and is not kept, so the next request lists again. A page's own `imageUrl` can be a bare path, so `pageAddress` asks the source what it would request.
- Page and cover requests use `HttpSource.imageCall`, which builds the request on the source's `client` (covers as a plain request with the source's headers, as Mihon does).
- The source's call runs for the app's call that asked (`executeFor`, `blockingFor`): cancelling the app's call cancels it, and for a call already cancelled the source is never asked, so none of the extension's own interceptors run either. OkHttp runs application interceptors even for a cancelled call, and extensions rate-limit their client per site (Asura Scans: two requests per two seconds), so without this a closed reader's leftover pages held the site's queue and the next chapter's page list waited behind them for tens of seconds. A shared chapter listing (`listings`) is not cancelled with its first caller, since other pages wait on it.
- `getImage` ignores Mihon's `existingSize`: the host always fetches the whole image.
- Addresses handed to the app end in a fragment naming the source by full key, so sources of two kinds with the same numeric id stay apart. `ExtensionImages` on the app's media client answers tagged addresses through that source and passes other requests on.
- A tag is honored only with an HMAC, under a per-install key, of the address and tag contents. A forged tag (bare tag, made-up code, a real code moved to another host) goes out as an ordinary request without the fragment, and no extension loads.
- The key is kept where Android does not back it up, so another install's key issues nothing this install honors.
- A stored cover whose tag names another source (from an earlier build or a backup) is still fetched by its own source.

## Consequences

Renaming the tag prefix orphans stored covers. Any new image source for extensions must go through `SourceImages`.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionImages.kt`: `ExtensionImages`, `issued`, `marker`, `publish`, `published`, `address`, `tag`, `TAG`, `FRAGMENT`, `SourceImages`, `executeFor`, `blockingFor`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSource.kt`: `sourceImages`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `imageKey`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/MihonImages.kt`: `MihonImages`, `pageAddress`, `fetch`, `unresolvedPage`, `imageCall`, `listings`, `MAX_PAGES`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt`: `imageCall`, `getImage`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionImagesTest.kt`: `pages missing together list their chapter once, and a failed listing is not kept (ADR 0059)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionImagesTest.kt`: `a reader closed mid-load hands its source's rate limit back to the next page list (ADR 0059)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionImagesTest.kt`: `a page the app no longer wants never reaches its source (ADR 0059)`
