# 0114. Browser checks run in one visible page, never leak credentials to the page, and media requests retry once with the user's key

Status: Accepted

## Context

Sites sit behind browser checks and some treat media files like their API. The check page can redirect anywhere, and the WebView keeps cookies in ways a plain client cannot overwrite. The core challenge rules live in the host-outage decision; this records the app-layer pieces.

## Decision

- `SiteChecks` is the one check page for every kind of source and for a site being added. After each page load the target's own client is tested, one test at a time, and the first pass completes the check and closes the page. Nothing is tapped or solved for the user. It is owned like a screen's state holder so a check outlives Activity recreation. While one is on screen its site gets no hidden browser check (which would expire the clearance the visible one just earned) and its `cookieHost` gets the browser's cookies. Only a refusal is remembered (`refusedCookies`): a test that failed on an error says nothing about the cookies, and a page that brings no new ones has nothing new to test. Cookies are flushed after a pass, because the WebView writes them lazily and a clearance lost to the app closing would mean another check. `SiteCheckOverlay` shows the title and host so the user always sees where the page went; closing by hand completes nothing.
- `WebViewChallengeBrowser` loads the page in an unseen WebView with JavaScript and DOM storage on and the request's agent. `browserHeaders` passes only `Accept-Language` and `Referer`; `Authorization`, `Cookie` and any source key stay out because the page can redirect anywhere. It handles only web schemes (never hands another app's scheme to another app), a dying renderer ends the pass instead of the app, and no main thread or a missing WebView counts as a failed pass. The WebView is made and destroyed on the main thread; the destroy is posted after the one that made it.
- `AndroidBrowserCookieStore.expire` overwrites a cookie in every form it can be stored in (host-only or on its creation domain, `Secure`, partitioned), because the WebView will not replace a `Secure` cookie with one without the flag, and a partitioned one (as Cloudflare's clearance is) only within its partition. Calls can throw when the WebView is missing or updating; callers guard them.
- `RefusedMediaKeyRetry` gives a refused media request one more try with the person's API key (a 401, 403 or HTML page with a success status counts as a refusal). Most sites never refuse, so they see exactly the request they always did; a host the key helped gets the key straight away from then on. `redirectGuard` sees every redirect hop because OkHttp keeps the header on a same-host redirect that can lead to another install on the host; it judges only keys added here.
- `SourceHealth`/`retryWhenVerified` retry a screen's load by itself once a check passes, but a need that merely expired retries nothing (the site would challenge again). `passSource` passes the site host even when its outage record has lapsed. The startup check sends no key, so sites with a saved key are left out (`startupCheckSources`); it runs once per process after the app is up so cached screens are never held back. A source's favicon is requested with the site's media headers so a verified site's clearance (bound to its User-Agent) applies.

## Consequences

A user sees and controls every browser check; a check never exposes credentials to the page it opens.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SiteChecks.kt`: `SiteChecks`, `SiteCheckTarget`, `OpenSiteCheck`, `SiteCheckOverlay`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/WebViewChallengeBrowser.kt`: `WebViewChallengeBrowser`, `browserHeaders`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/AndroidBrowserCookieStore.kt`: `AndroidBrowserCookieStore`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/RefusedMediaKeyRetry.kt`: `RefusedMediaKeyRetry`, `redirectGuard`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceHealth.kt`: `retryWhenVerified`, `passSource`, `startSourceHealthCheck`, `startupCheckSources`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceLogo.kt`: `FaviconImage`
