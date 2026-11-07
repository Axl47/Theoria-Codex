# Custom-site probe fixtures

Metadata responses captured on 2026-11-07 with host `curl` (User-Agent
`TheoriaCodex/1.0 (Android source adapter)`) from the exact requests `SiteProber` sends. Each
folder's `routes.json` records path, query, status, content type and any `Location` /
`WWW-Authenticate` header; the body files sit beside it. `FixtureSiteClient` serves them in tests
and fails on any request a fixture does not cover.

Redaction: post tags, hashes, sources, uploader names and media URLs are replaced (`tag_a tag_b`,
`redacted`, `https://<host>/redacted/media.<ext>`). Ids, counts and field names are original, so
the response shapes are real. `aibooru/invalid_api_key_401.json` and
`aibooru/access_denied_403.json` are Danbooru's error envelope for a bad key and for a
members-only endpoint (backtraces emptied); they are standalone evidence, not routes. HTML interstitials keep their first 2 KB without scripts or styles.
Bodies are evidence of protocol shape only; no media was fetched.

| Folder | Evidence |
| --- | --- |
| `danbooru` | Maintenance: 503 HTML "Downbooru" (the site is a built-in anyway). |
| `aibooru` | Danbooru: `/counts/posts.json` → `{"counts":{"posts":N}}`; anonymous `/profile.json` has `tag_query_limit`; the DAPI path is a Cloudflare challenge. |
| `safebooru` | Gelbooru 0.2: DAPI XML `<posts count="N">`, `json=1` array. |
| `gelbooru` | Gelbooru 0.2 refusing keyless access: DAPI 401 with an empty body and no `WWW-Authenticate`. |
| `rule34xxx` | Gelbooru 0.2 refusing keyless access: DAPI 200 `<error>Missing authentication…</error>` / JSON string. |
| `sakugabooru` | Moebooru: `/post.json` array with `md5` and `file_url` (unsupported family). |
| `sakugabooru_apex` | The bare domain 301-redirects to `www.`. |
| `example` | Ordinary HTML site: 404 HTML everywhere. |
