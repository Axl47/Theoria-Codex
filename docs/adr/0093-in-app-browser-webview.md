# 0093. The in-app browser is a locked-down WebView that lives as long as its address and agent

Status: Accepted

## Context

The in-app browser exists to pass site challenges and hand back the clearance cookie. A WebView must be destroyed only once detached, reports page events out of order, and can lose its renderer under memory pressure.

## Decision

- `rememberBrowserState` keeps one browser alive for as long as its `url` and `userAgent` do, so recomposition never reloads it. `BrowserState.userAgent` is exposed because a clearance cookie the browser earns is bound to that agent.
- The WebView reads neither files nor content providers and loads only web addresses. Another app's scheme is ignored, never handed to that app. It paints the theme background until the page does, avoiding a white flash in dark theme.
- Only main-frame loads are reported. `onPageLoaded` fires after a page that loaded, and `loadFailed` only when a page could not load at all. An HTTP error such as a challenge's 403 is a page that loaded, reported with success false. The error flag is set by the error and read when the page finishes, whichever order the page's start and the error arrive in.
- A WebView created after its renderer died replaces the old one, which is released and destroyed.
- `release` runs once the view is detached, as `destroy()` requires. The page's callbacks are cleared first, because stopping a load reports its end later and nothing may touch the view after it is destroyed.

## Consequences

Anything reading the clearance cookie must use the browser's own agent. Changing what the WebView may load, or when it is destroyed, needs the same ordering care.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/BrowserPage.kt`: `BrowserState`, `rememberBrowserState`, `BrowserPage`, `createBrowserWebView`, `release`
