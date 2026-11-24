# 0045. A page is full when the raw record count says so, before filtering

Status: Accepted

## Context

Adapters drop deleted comments, malformed entries and duplicates when mapping provider records. If continuation were decided from the filtered list, a page that lost a few records would look like the last page and paging would end early.

## Decision

- Whether another page may follow is judged on the raw record count: Danbooru comments (`records.size() >= DANBOORU_COMMENTS_PAGE_SIZE`), Danbooru posts (paging ends when `rawRecords.size() < PAGE_SIZE`) and Gelbooru posts (`rawPosts.size >= PAGE_SIZE`). Danbooru's raw count includes non-object array entries; they are dropped only afterwards, and an id cursor then comes from the object records' lowest id. Gelbooru's `rawPosts` already excludes non-object entries (`objectList`); only the later `parsePost` step, which drops records without an id, comes after the count.
- A comments page that comes back full may have a successor; the total is known only when everything fit on the first page.
- `canonicalPostPage` de-duplicates by id while keeping provider order and the continuation computed from raw records.

## Consequences

Every adapter that filters records must compute its next-page token before filtering. A provider with a total count could use it instead.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruComments.kt`: `DANBOORU_COMMENTS_PAGE_SIZE`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapter.kt`: `comments`, `searchTags`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`: post paging
- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/CanonicalPostPage.kt`: `canonicalPostPage`
