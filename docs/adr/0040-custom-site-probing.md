# 0040. Adding a custom site runs a bounded read-only probe over protocol families

Status: Accepted

## Context

Users add booru sites by address. The app must decide which supported API dialect a site speaks without storing anything, flooding the site, or leaking keys, and must recognise it even when the site is empty or restricted.

## Decision

- `SiteProber` is read-only and bounded: overall `deadlineMs`, at most `MAX_REQUESTS` = 8 logical GETs (worst case: a keyed Danbooru match and its anonymous repeat of three requests each, the home page and the touch icon), no retries, nothing persisted. `BudgetedHttpClient` fails any `get` past the budget. Each logical GET can follow up to `MAX_REDIRECTS` = 3 redirect hops inside `CustomSiteHttpClient`, a separate limit that the budget does not count. Probes use a `CustomSiteHttpClient` with no retry or outage tracker, so they see raw answers.
- Families recognise a site from protocol evidence that exists on empty or restricted sites, never from a parsed post. Danbooru is probed first because its `/counts/posts.json` is cheap and Gelbooru sites answer it with a plain 404.
- A match then needs `verifyWithAdapter`: the real adapter reads the newest page under the identity the site would get (an empty page passes).
- With credentials the keys are what is tested, so a key works where anonymous access is closed and a bad one is reported. A saved site's known family is tried first and skips the anonymous family re-probe; the home-page and touch-icon lookups still run anonymously. An open site keeps its guest limits even if the key is later removed.
- Gelbooru 0.2 is recognised by the DAPI `<posts count>` envelope (the adapter reads `json=1`, so a match also needs the adapter to work). Keyless refusal shapes differ: Gelbooru answers 401 with an empty body, rule34.xxx answers 200 with an error; an empty 401 with `WWW-Authenticate` is HTTP auth in front of another site, and HTML pages are never auth evidence. Unknown forks get a conservative profile: OR groups expanded by the orchestrator, tag list format learned on first use, Gelbooru-only extras off.
- A browser verification gate needs the word `challenge` plus independent corroboration, so pages that merely mention verification stay `NoMatch`. Browser challenges (any status, and 403/503 HTML) and server errors are inconclusive; other unclaimed answers are `NoMatch`.
- The home page is best effort for the label and icon, and the icon URL in the result is nullable. A failed home-page request leaves the host as label; the `/apple-touch-icon.png` lookup still runs and is used when it answers 200 with any `image/` content type. That lookup also runs when the declared icon is missing or small (under 64 px; a sizeless `icon` is assumed 16, a sizeless apple icon 180). Declared icons must be HTTPS and not SVG (by `.svg` extension or `type`), because media loads do not decode SVG.
- `FixtureSiteTransport` serves the captured fixtures under `custom-sites/` as a redirect-free transport keyed by host, path and exact query. Any request no fixture covers fails the test.
- Credential parameters are ignored when no route names them.
- `SiteProberTest` builds the prober with `builtInHosts = emptyMap()` so shipped sites' fixtures exercise the families as if user-added.

## Consequences

Adding a family means adding evidence that works on empty sites and a place in the probe order. Raising the request budget raises load on arbitrary user-supplied hosts.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/SiteProber.kt`: `SiteProber`, `MAX_REQUESTS`, `BudgetedHttpClient`, `homePage`, `siteIcon`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/SiteFamily.kt`: `SiteFamilyRegistry.default`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/Families.kt`: `DanbooruFamily`, `GelbooruFamily`, `verifyWithAdapter`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/ProbeEvidence.kt`: `isGelbooruAuthRefusal`, `isVerificationChallenge`, `unclaimed`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/CustomSiteHttpClient.kt`: `retry` (adapters only), `MAX_REDIRECTS`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/FixtureSiteTransport.kt`: `FixtureSiteTransport`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/SiteProberTest.kt`: `prober`
