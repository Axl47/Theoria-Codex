# 0057. Extensions get a Mihon-like HTTP client that shares browser cookies and never lets a challenge page through

Status: Accepted

## Context

Extensions are written against Mihon's client and many keep sessions in cookies. A clearance cookie from a browser check is only accepted from the address it was earned from. Extensions parse whatever answer they get, so a challenge page reaching them would be misread as content.

## Decision

- `extensionClient` uses Mihon's timeouts and the three named interceptors in Mihon's order, none of the app's interceptors, and the system browser's cookies for every host. It is deliberately not limited to hosts the app's clients may send browser cookies to (`SiteCookieBridge`) because extension code runs in the app's process and could read the store anyway. It resolves through `BrowserAddressDns`.
- `CloudflareInterceptor` tries a hidden pass, then records a standing challenge in `outages` with the refused request and agent and fails as `HostUnavailableException`; the source shows as needing browser verification and the check page passes it with that agent. While a record lasts no hidden pass is tried and the record is not renewed, so it still expires on time; a successful answer clears it; a record another client made without the request gains this one's.
- Non-challenge refusals (403/503) are logged once per host and status, naming the server only as Cloudflare or not (the header is the site's free text), recording whether a clearance was carried.
- The default user agent is Mihon's, which extensions are written and tested against. The Application handed to extensions is the real one except for preferences.

## Consequences

Cookie isolation between extension sources and the app is not a security boundary. A challenged API or image host is attributed to the source that called it.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `ExtensionHost`, `extensionClient`, `DEFAULT_USER_AGENT`, `clearsChallenge`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`: `NetworkHelper`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt`: `CloudflareInterceptor`, `reportRefusal`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionHostTest.kt`: `extensions get the app's own application and context, except that every preferences name is a no-backup file of the host's (ADR 0057, 0061)`, `extensions are handed the extension client, with Mihon's interceptors and timeouts, the browser's cookies for every host and its DNS (ADR 0056, 0057)`
- `extension-host/src/test/kotlin/eu/kanade/tachiyomi/network/interceptor/HostInterceptorsTest.kt`: `the client extensions rebuild carries the interceptor names they require, in Mihon's order (ADR 0056, 0057)`, `a challenge no hidden pass clears is recorded with the agent it refused, and never reaches the extension (ADR 0050, 0057)`, `a challenge another client recorded without the refused request gains the extension's, keeping its expiry (ADR 0050, 0057)`, `a challenged host that answers successfully again stops needing verification, and only that host (ADR 0050, 0057)`, `a refusal without a challenge reaches the extension and is logged once per host and status, without the server's text (ADR 0057)`
- Left to prose: the default user agent being the one Mihon's extensions are tested against is a value chosen by observation (the tests check that a request's own agent wins and the default fills in, not which browser it names); the check page passing a standing challenge with the recorded agent is app behaviour; cookie isolation not being a security boundary is a consequence, not a rule.
