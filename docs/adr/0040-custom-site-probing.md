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

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/CustomSiteHttpClientTest.kt`: `stops after three redirect hops (ADR 0040, 0051)`, `a probe without a retry policy sees the first answer raw (ADR 0040)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/FamilyAdapterTest.kt`: `a custom Gelbooru site expands OR groups client-side and sends keys as user_id (ADR 0040)`, `an XML-only tag list switches suggestions and counts to autocomplete once (ADR 0040, 0042)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/ProbeEvidenceTest.kt`: `HTTP authentication and HTML denial pages are not Gelbooru refusals (ADR 0040)`, `an empty Gelbooru site still shows its envelope (ADR 0040)`, `a verification challenge page is blocked, whatever its status (ADR 0040)`, `ordinary pages are not verification challenges (ADR 0040)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/SiteProberTest.kt`: `AIBooru is Danbooru with its measured guest term limit (ADR 0040)`, `an envelope the family adapter cannot read is not locked in (ADR 0040)`, `Gelbooru's empty 401 and rule34's missing-authentication reply ask for keys (ADR 0040)`, `a working key gets through where anonymous access is refused with a page (ADR 0040)`, `a bad key is reported even on a site open to everyone (ADR 0040)`, `a good key on an open site leaves the site usable without keys (ADR 0040)`, `a saved site is checked with its keys alone (ADR 0040)`, `a maintenance page makes the probe inconclusive (ADR 0040)`, `the overall deadline ends a hanging probe (ADR 0040)`, `a keyed Danbooru match with its anonymous repeat spends exactly the default request budget (ADR 0040)`, `a failed home page leaves the host as label and the touch icon still answers (ADR 0040)`, `the touch icon replaces a declared icon under 64 px and leaves one of 64 px (ADR 0040)`, `a declared icon must be HTTPS and not SVG by extension or type (ADR 0040)`, `site icon is the largest declared raster icon, resolved against the page (ADR 0040)`
- Left to prose: nothing is persisted because `SiteProber` takes no store; the production probe client being built without retries or an outage tracker is the app container's wiring, guarded with the app batch; `FixtureSiteTransport`'s fixture rules and the empty `builtInHosts` in `SiteProberTest` are harness conventions every probe test depends on; unknown forks keeping Gelbooru-only extras off follows from `GelbooruSite`'s defaults.
