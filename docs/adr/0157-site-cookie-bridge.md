# 0157. Browser cookies and site keys reach requests only for the hosts the user set up or verified

Status: Accepted

## Context

Sites behind browser challenges need the clearance cookies earned in the in-app browser, and some saved sites accept an API key in an `Authorization` header. Sending either to the wrong host would leak a clearance or a key, and a missing or updating WebView must never fail a request.

## Decision

- `SiteCookieBridge` gives the browser's cookies only to saved custom sites, built-in sites verified in the browser, the one site being set up in the Add form (`setSetupHost`), and the one whose browser check is on screen (`setCheckHost`), plus their subdomains. Every other host gets none, so an unverified built-in source behaves exactly as before. Every store call is guarded: a missing or updating WebView means no cookies, never an exception on a request thread. Every OkHttp client in the app carries `cookieJar`, which goes through this gate.
- The setup host and the check host are separate so a check ending never takes away the Add form's host, nor the reverse.
- A verified built-in site's clearance is bound to the browser's User-Agent, so `BuiltInAgentStore` keeps it by host (built-in sites have no profile; a saved custom site keeps its agent in its profile) and clearance survives restarts.
- `mediaAuthorizationFor` returns a key only for HTTPS, on one saved site's own port and hosts, and under its install path on its main host; installs that cannot be told apart get no key, so none reaches another. `SiteMediaKey` is a plain class so the value cannot reach a log through `toString`.
- `keepsCookies` marks hosts that keep the browser's cookies for good (saved custom sites and hand-verified built-ins). The app's own requests try a hidden browser check only for these, so an expired clearance renews out of sight, a never-verified built-in site still asks the user, and a hidden check never widens which hosts get cookies.
- `isAllowed` runs on every image and API request, so it allocates nothing and parses nothing beyond host normalization. `forget` drops a removed site's cookies from the shared store.

## Consequences

A new place that makes site requests must use the shared clients to get the gate. Widening which hosts receive cookies or keys needs a decision here first.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/customsites/SiteCookieBridge.kt`: `SiteCookieBridge`, `SiteMediaKey`, `mediaAuthorizationFor`, `keepsCookies`, `isAllowed`, `setSetupHost`, `setCheckHost`, `forget`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/customsites/BuiltInAgentStore.kt`: `BuiltInAgentStore`
