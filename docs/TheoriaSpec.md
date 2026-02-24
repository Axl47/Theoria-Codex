---
created_at: 2026-02-24T17:45
updated_at: 2026-02-24T17:59
---
# Theoria Codex — UI Spec v1 (Android / Portrait-only MVP)

## 0) Purpose

Theoria Codex is an Android-first, local-first, tag-driven art browser designed around two primary activities:

1. Discover: build precise tag queries and browse results quickly.
2. Collect: save posts into user-defined collections called **Codices** (“Boards”).

MVP is implemented **end-to-end using stub source adapters** (JSON fixtures). Real Pixiv/Gelbooru/AIBooru integration is a second pass; the UI, state model, persistence, and adapter contracts must not change when real sources are added.

---

## 1) MVP Scope Summary

Included:

* Tabs: **Search**, **Explore**, **Codex**, **Settings**
* Query-first Search with chips + autocomplete
* Mode toggle: **Unified / Pixiv / Gelbooru / AIBooru** (Unified works with stubs now)
* Filter/Sort bottom sheet
* Apply model: changes are staged and applied only on explicit **Apply**
* Results: 2-column **staggered** masonry grid (portrait-only)
* Viewer: full-screen immersive viewer with swipe + zoom/pan + save actions
* Codex: create/rename/delete; add/remove items; sort-only (no manual reorder), no notes
* Local-first persistence: Codices + items + recent/saved queries + cached thumbnails

Deferred (not in MVP):

* Desktop/web
* Landscape layout work
* Tablet-specific layout tuning
* Manual reorder in Codex
* Notes per item
* Rating/NSFW gating UX

---

## 2) Global Design System

### 2.1 Layout & spacing (dp)

Base grid: 8dp.

* XS: 4
* S: 8
* M: 16
* L: 24
* XL: 32

Global rules:

* Screen horizontal padding: 16dp (phones)
* Vertical padding: 12dp (top-level sections unless otherwise specified)
* Inter-section spacing: 12dp
* Minimum touch target: 48dp

### 2.2 Components

* Corner radius:

  * Tiles (image cards): 16dp
  * Bottom sheets: 24dp top corners
  * Chips: pill (999dp)
* Tile spacing (grid):

  * Outer padding: 16dp
  * Gutter between tiles: 6dp

### 2.3 Typography (Material 3)

* Screen title: TitleLarge
* Section header: TitleMedium
* Body: BodyMedium
* Secondary/meta: BodySmall

### 2.4 Color / theme

* Use Material 3 dynamic color if available.
* Background uses `surface` / `surfaceContainer`.
* Avoid heavy borders in grids; rely on spacing and consistent rounded corners.

### 2.5 Portrait-only MVP rule

* App locks to portrait orientation for MVP.
* Landscape is allowed to exist but is not optimized; acceptable behavior is simple scaling without layout rearrangement.

---

## 3) Navigation Model

Bottom navigation bar (4 items):

1. Search
2. Explore
3. Codex
4. Settings

Rules:

* Bottom nav is always visible on top-level screens.
* Sub-screens (Viewer, Codex detail) use standard navigation without bottom nav (immersive).

---

## 4) Core Interaction & Gesture Contract

### 4.1 Grid interactions (Search results and Codex detail)

* Tap tile: open Viewer at that post.
* Long-press tile: open **Quick Actions** bottom sheet:

  * Save to Codex
  * Open in browser (if page URL available)
  * Share link (optional; may be omitted in MVP)

### 4.2 Viewer interactions

* Swipe left/right: next/previous item in current stream.
* Swipe down: dismiss to grid (restore exact scroll position).
* Single tap: toggle chrome (top/bottom overlays).
* Double tap: zoom toggle (fit → 2x → fit).
* Pinch: continuous zoom.
* Pan: enabled only when zoom > fit.
* Swipe up: open **Info/Actions** bottom sheet.

---

## 5) Data Contracts (Domain Models)

### 5.1 SourceKey

Enum:

* `PIXIV`
* `GELBOORU`
* `AIBOORU`

### 5.2 PostId

Stable composite ID (required everywhere):

* `source: SourceKey`
* `sourcePostId: String`

### 5.3 Post (minimum schema)

```kotlin
data class Post(
  val id: PostId,
  val preview: ImageRef,      // thumbnail/preview used in grids
  val full: ImageRef?,        // full image for viewer; may be null if unavailable
  val pageUrl: String?,       // source page
  val width: Int?,            // preferred; used for aspect ratio/masonry stability
  val height: Int?,           // preferred
  val canonicalTags: List<String>,
  val rawTags: List<String>,  // may be empty in stubs, required for pass 2
  val authorName: String?,    // optional
  val createdAtEpochMs: Long? // optional
)

data class ImageRef(
  val url: String?,           // remote URL (pass 2)
  val localPath: String?,     // if cached locally
  val mime: String?           // optional
)
```

### 5.4 Query (Draft/Applied)

```kotlin
data class Query(
  val mode: QueryMode,
  val includeTags: List<String>,
  val excludeTags: List<String>,
  val sort: SortMode,
  val dateRange: DateRange?,     // optional in MVP (used for quick queries)
  val minScore: Int?             // optional; may not be supported by all sources
)

sealed class QueryMode {
  data object Unified : QueryMode()
  data class Source(val source: SourceKey) : QueryMode()
}

enum class SortMode { NEWEST, POPULAR, TOP, RANDOM }
```

### 5.5 Codex (Boards)

* “Codex” is a named collection of saved posts.

```kotlin
data class Codex(
  val codexId: String,
  val name: String,
  val createdAtEpochMs: Long
)

data class CodexItem(
  val codexId: String,
  val postId: PostId,
  val savedAtEpochMs: Long
)
```

### 5.6 Persistence expectations

Persist locally:

* Codices and CodexItems
* Cached posts metadata for any post saved to Codex
* Thumbnail cache for any post saved to Codex (must exist offline)
* Recent queries and saved queries (recommended; required for MVP UX)
* Settings

---

## 6) Adapter Contracts (Stub-first)

### 6.1 SourceAdapter interface

```kotlin
interface SourceAdapter {
  val sourceKey: SourceKey
  val capabilities: SourceCapabilities

  suspend fun search(query: Query, pageToken: String?): Page<Post>
  suspend fun trendingTags(limit: Int): List<TagSuggestion>
  suspend fun quickQuery(kind: QuickQueryKind): Query // app-defined templates

  // Optional for MVP (can be no-op in stubs)
  suspend fun resolvePost(id: PostId): Post?
}

data class Page<T>(
  val items: List<T>,
  val nextPageToken: String?
)

data class TagSuggestion(
  val text: String,
  val type: String?,  // "character" | "artist" | "meta" | "unknown"
  val count: Int?
)

data class SourceCapabilities(
  val supportsSortNewest: Boolean,
  val supportsSortPopular: Boolean,
  val supportsSortTop: Boolean,
  val supportsSortRandom: Boolean,
  val supportsExcludeTagsServerSide: Boolean,
  val supportsDateRangeServerSide: Boolean,
  val supportsMinScoreServerSide: Boolean
)

enum class QuickQueryKind {
  POPULAR_TODAY, TOP_7D, TOP_30D, NEWEST, RANDOM
}
```

### 6.2 Unified execution (strict behavior, even with stubs)

* Unified mode runs `search()` on each enabled adapter concurrently.
* If a query requires a capability a source lacks, that source is excluded for that run and shown in “status pills” (see Search states).
* Results are merged using a weighted interleave policy (weights configurable in Settings; defaults provided below).

---

## 7) Stub Fixture Format (Self-contained)

All stub data lives in app assets (or local test directory). Fixtures must simulate:

* Paging
* Empty results
* Failures/timeouts per source
* Trending tags
* Quick query templates

### 7.1 File structure

```
assets/stubs/
  pixiv/
    search_page_1.json
    search_page_2.json
    trending_tags.json
  gelbooru/
    search_page_1.json
    search_page_2.json
    trending_tags.json
  aibooru/
    search_page_1.json
    search_page_2.json
    trending_tags.json
  scenarios/
    failures.json
    empties.json
```

### 7.2 search_page_N.json schema

```json
{
  "nextPageToken": "page_2",
  "items": [
    {
      "sourcePostId": "12345",
      "previewUrl": "https://example.com/thumb.jpg",
      "fullUrl": "https://example.com/full.jpg",
      "pageUrl": "https://example.com/post/12345",
      "width": 1200,
      "height": 1800,
      "canonicalTags": ["tag1", "tag2"],
      "rawTags": ["tag1", "tag_2"],
      "authorName": "artist",
      "createdAtEpochMs": 1700000000000
    }
  ]
}
```

### 7.3 trending_tags.json schema

```json
{
  "items": [
    { "text": "tag1", "type": "character", "count": 12000 },
    { "text": "tag2", "type": "meta", "count": 8000 }
  ]
}
```

### 7.4 failures.json schema (scenario switch)

```json
{
  "sources": {
    "PIXIV": { "failSearch": false, "failTrending": false, "delayMs": 0 },
    "GELBOORU": { "failSearch": true, "failTrending": false, "delayMs": 1200 },
    "AIBOORU": { "failSearch": false, "failTrending": false, "delayMs": 0 }
  }
}
```

---

## 8) Screens — Exact Layout Specifications

## 8.1 Search Screen (Tab)

Purpose: build a query (chips) and browse results. Changes are staged in DraftQuery until Apply.

### 8.1.1 Structure

Top-level layout: Column (fills screen)

A) **Top Area** (sticky)

1. Query Bar (collapsible)
2. Mode Toggle (Unified / Source)
3. Optional status pill row (Unified partial failures)
4. Results header row (optional minimal; e.g., count; can be omitted in MVP)

B) Results Grid (fills remaining space)
C) Apply Bar (appears only when DraftQuery != AppliedQuery)
D) Filter FAB (bottom-right)

### 8.1.2 Query Bar

Height:

* Collapsed: 56dp
* Expanded: up to 120dp (max 2 lines of chips + input)

Padding:

* Outer: 16dp horizontal, 12dp vertical

Contents (left to right):

* Search icon: 24dp, centered vertically
* Chips + text input area:

  * Chips wrap within max 2 lines
  * Input is inline after last chip
* Trailing icon slot:

  * If any text or chips: clear (×)
  * Else: history icon

Behavior:

* Tap inside focuses input and expands.
* When expanded and autocomplete visible, the bar remains expanded.
* Clear (×) clears DraftQuery tags and input text (does not automatically apply).

### 8.1.3 Mode Toggle Row

Height: 40dp
Padding: 16dp horizontal, 8dp top, 8dp bottom
Component: segmented control with 4–5 options:

* Unified
* Pixiv
* Gelbooru
* AIBooru

Rules:

* Selecting a mode changes DraftQuery.mode (not applied until Apply).
* Unified mode shows small text “(n sources enabled)” under the segmented control if space permits; otherwise in Settings only.

### 8.1.4 Autocomplete Panel

Displayed when input has ≥1 character.

* Anchored under Query Bar (over content).
* Height: up to 320dp; scrollable.
* Row height: 52dp
* Row layout:

  * Left: tag text (primary)
  * Under/secondary: type (optional)
  * Right: count (optional)

Interactions:

* Tap row: add include chip to DraftQuery.includeTags
* Swipe left on row: add exclude chip to DraftQuery.excludeTags
* Chips added remove the current input text.

### 8.1.5 Filter FAB

* Size: 56dp
* Position: bottom-right above Apply Bar if visible, otherwise above bottom nav with 16dp margin.
* Icon: filter/sliders

Tap opens **Filter/Sort Sheet** (8.1.7).

### 8.1.6 Results Grid

* 2 columns, staggered heights.
* Tile height conforms to image aspect ratio (use width/height if known; else use thumbnail intrinsic).
* Tile radius: 16dp.
* Gutter: 6dp.
* Outer padding: 16dp.

Tile overlay (minimal):

* Top-left source badge:

  * Height: 20–24dp
  * Padding: 8dp horizontal, 2–4dp vertical
  * Text: source short label (PX / GB / AI) or full
* No other text.

Grid states:

* Loading: skeleton tiles in same grid.
* Empty: centered empty state block (see 9).
* Error: inline banner and retry (see 9).

### 8.1.7 Filter/Sort Sheet (Bottom sheet)

Padding: 16dp all around

Sections (each separated by 12dp):

1. Sort

* radio group: Newest / Popular / Top / Random

2. Date Range (optional MVP; still show for templates)

* chips: Today / 7d / 30d / Custom (Custom can be disabled in MVP)

3. Min Score (optional)

* numeric input (disabled if mode/source does not support; still allowed in DraftQuery with strict exclusion later)

Bottom buttons (sticky within sheet):

* Reset (left)
* Done (right) — closes sheet and keeps DraftQuery updated (still not applied)

### 8.1.8 Apply Bar (sticky, bottom)

Visible only when DraftQuery != AppliedQuery.

Height: 64dp
Padding: 16dp horizontal, 8dp vertical
Layout:

* Left: “Reset”
* Right: “Apply” (primary)

Reset:

* DraftQuery := AppliedQuery
* Close autocomplete, collapse query bar

Apply:

* AppliedQuery := DraftQuery
* Trigger new paging stream, show skeleton

---

## 8.2 Explore Screen (Tab)

Purpose: discovery without typing, but still query-first.

### 8.2.1 Structure

* Vertical scroll
* Top: Quick Queries
* Below: Trending Tags

Padding: 16dp horizontal

### 8.2.2 Quick Queries

Card list or grid (2-column small cards is acceptable).

Cards:

* Popular Today
* Top 7d
* Top 30d
* Newest
* Random

Card size:

* Height: 88dp
* Radius: 16dp
* Internal padding: 16dp

Tap:

* Builds DraftQuery using adapter.quickQuery(kind) (or app templates in stub).
* Navigates to Search tab.
* Shows Apply Bar immediately (Draft differs from Applied) OR auto-applies (MVP rule: do not auto-apply; show Apply Bar).

### 8.2.3 Trending Tags

Header: “Trending tags”
Then horizontal chip row:

* Chip height: 32–36dp
* Inter-chip spacing: 8dp

Tap chip:

* Adds include tag to DraftQuery.
* Navigates to Search tab and shows Apply Bar.

Trending states:

* Loading: chip skeletons
* Error: “Retry” inline row

---

## 8.3 Viewer (Fullscreen)

Purpose: immersive consumption + fast save to Codex.

### 8.3.1 Structure

* Full screen image canvas
* Chrome overlays (toggle with single tap)
* Info/Actions bottom sheet (swipe up)

### 8.3.2 Image canvas

* Default scale: fit center
* Supports pinch zoom/pan
* Prefetch: preload next/prev preview image for smoother swipe (implementation detail)

### 8.3.3 Chrome overlays (visible when toggled on)

Top bar (height 56dp, translucent surface):

* Left: back icon (optional; swipe down still supported)
* Center: source badge + optional index (“12 / 200”)
* Right: overflow menu

Bottom bar (height 72dp, translucent surface):

* Primary action: Save (icon + label)
* Secondary: Codex shortcut (optional) or “Info” button (opens sheet)
  Rules:
* Save must be reachable with one hand. Place it near bottom center/right.

Auto-hide:

* Chrome hides after 1.5 seconds of no interaction.

### 8.3.4 Save flow (UX-first)

Tap Save:

* Opens “Save to Codex” bottom sheet (8.4.4).
* If only one Codex exists, allow “Quick save” without list (optional): tap Save saves to last-used Codex and shows snackbar with “Change” action to pick a different codex.

### 8.3.5 Info/Actions sheet

Opened by swipe up or by Info button.

Sections:

* Primary actions:

  * Save to Codex
  * Open in browser (if pageUrl exists)
* Tags (scrollable list):

  * Each tag row:

    * Tap: add include tag to DraftQuery (does not apply)
    * Swipe left: add exclude tag
* Metadata (optional in MVP): author, created date (if exists)

When tag is added from Viewer:

* Show snackbar: “Added tag to search (Apply to update)” with “Go to Search” action.

---

## 8.4 Codex Screen (Tab)

Purpose: manage Codices and view collected items offline.

### 8.4.1 Codex List Screen

Top row:

* Title: “Codex”
* Right: + Create

List items:

* Height: 84dp
* Left: cover collage (2x2 thumbnails), 64dp square, radius 12dp
* Center: Codex name (TitleMedium)
* Bottom/secondary: “N items”
* Right: overflow (⋮)

Spacing:

* Screen padding: 16dp
* Item spacing: 12dp

Empty state:

* Title: “No codices yet”
* Button: “Create codex”

Overflow actions:

* Rename
* Delete

### 8.4.2 Create/Rename Codex dialog

Material dialog or bottom sheet:

* Title
* Text field (name)
* Cancel / Save

### 8.4.3 Codex Detail Screen

Header:

* Codex name
* Item count
* Sort control (chips or dropdown):

  * Newest saved (default)
  * Oldest saved
  * By source

Grid:

* Same as Search grid (2 columns staggered, 6dp gutter)
* Offline guaranteed thumbnails

Long-press tile:

* Quick actions:

  * Remove from codex
  * Open in viewer

Empty:

* “This codex is empty”
* CTA: “Browse and save posts”

### 8.4.4 Save to Codex bottom sheet

Header: “Save to Codex”
Top row:

* “New Codex” button
  List:
* Existing codices (row height 56dp)
  Tap row:
* Saves immediately
* Dismiss sheet
* Snackbar: “Saved to {CodexName}” with action “Open”

---

## 8.5 Settings Screen (Tab)

Purpose: configure unified behavior, caching, stub scenarios, and later sources.

### 8.5.1 Sections (scrollable)

Padding: 16dp horizontal

A) General

* Default mode on open:

  * Unified / last used (choose “last used” default)
* Apply behavior:

  * “Apply button required” (fixed true for MVP; show as info, not toggle)
* Portrait-only notice (info)

B) Unified mode

* Enabled sources toggles:

  * Pixiv (enabled by default)
  * Gelbooru (enabled by default)
  * AIBooru (enabled by default)
* Merge weights (sliders)

  * Default: Pixiv 0.5, Gelbooru 0.3, AIBooru 0.2
  * Sum must be 1.0 (normalize automatically)
* “Strict unified enforcement” (info label; fixed true)

C) Storage & caching

* Toggle: “Cache full image when saving to Codex”

  * Default OFF
  * Description: “Always saves metadata + thumbnail. When enabled, also caches the full image for offline viewing.”
* Button: “Clear thumbnail cache”
* Button: “Clear full image cache” (disabled if toggle off and none present)
* Cache size summary (optional MVP; can be placeholder)

D) Developer (MVP stubs)

* Scenario selector:

  * Normal
  * Partial failure (e.g., Gelbooru fails)
  * Empty results
  * Slow network simulation
* Button: “Reload fixtures”

---

## 9) Standard States (Reusable Components)

### 9.1 Skeleton loading

* Grid skeleton tiles fill the viewport with shimmer.
* Autocomplete skeleton rows if tag list loads asynchronously.

### 9.2 Empty state template

Centered block, max width 320dp:

* Title (TitleMedium)
* Body (BodyMedium)
* Buttons row:

  * Primary action
  * Secondary action (optional)

### 9.3 Error banner (inline)

Full-width banner under top area:

* Text: short (“Couldn’t load results”)
* Button: Retry
* Optional: Details dropdown (dev only)

### 9.4 Unified status pills

Row under mode toggle (only if Unified and any source excluded/failed):

* Pill per source with state:

  * “Pixiv excluded” / “Gelbooru failed”
* Tap pill opens sheet:

  * Reason
  * Retry (for failure)
  * Disable toggle shortcut (optional; may be included)

---

## 10) State Management Rules (Restoration)

### 10.1 Must restore within session

* Return from Viewer → exact Search grid scroll position
* Viewer index within current stream

### 10.2 Must persist across app restarts

* Last selected tab
* Last AppliedQuery per mode
* DraftQuery resets to AppliedQuery on cold start (MVP simplification)
* Codices and items
* Settings (weights, enabled sources, caching toggle)
* Thumbnail cache for saved Codex items

### 10.3 Query identity (for scroll restoration)

Compute a stable `queryHash` from AppliedQuery:

* mode
* include tags (sorted)
* exclude tags (sorted)
* sort
* dateRange/minScore if present

Store `scrollOffset` keyed by `queryHash`.

---

## 11) MVP Build Checklist (Self-contained “Definition of Done”)

UI completeness:

* Search screen with Draft/Apply, autocomplete, filter sheet, grid, all states
* Explore screen with quick queries and trending tags
* Viewer with swipe navigation and zoom/pan, save integration, info sheet
* Codex list/detail with create/rename/delete, save flow, remove item
* Settings with unified toggles/weights, cache toggle, stub scenario controls

Local-first:

* Codices persist across restarts
* Saved items show thumbnails offline
* Optional full image caching via setting works (if enabled)

Adapter readiness:

* SourceAdapter interface implemented for stubs
* Fixtures provide at least:

  * 2 pages of results per source
  * trending tags per source
  * failure and empty scenarios

Modularity:

* Replace stubs with real source adapters later without changing UI screens or domain models.

---

## 12) Default Settings (MVP)

* Default mode: last used (initially Unified)
* Enabled sources: all on
* Weights: Pixiv 0.5, Gelbooru 0.3, AIBooru 0.2
* Cache full image on Codex save: OFF
* Apply model: Apply button required (fixed)

---
