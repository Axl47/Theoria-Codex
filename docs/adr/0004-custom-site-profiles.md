# 0004. Custom booru sites get ids compatible with existing encodings, validated HTTPS roots and secret-safe credentials

Status: Accepted

## Context

Users add booru sites by address. Ids are persisted with every post and cache file, and must stay compatible with existing source-id encodings. Headers and credentials come from user input.

## Decision

- `CustomSourceIds`: `custom_` plus host with a leading `www.` removed and dots as underscores, then `__p<port>` for a non-default port and `__h<8 hex>` (the first 8 hex digits of the path's SHA-256) for an install path. The id is derived, not proven unique: `www.example.org` and `example.org` share an id, and different paths could in principle share a truncated hash. The alphabet avoids every separator other source-id encodings use (`:`, `.`, `/`, `,`, `|`). `MAX_LENGTH` 96 keeps `custom_<id>_<postId>.<ext>` cache file names below 255 bytes. A profile's stored id is authoritative; `fromAddress` only names new profiles.
- `SiteAddress.parse` treats schemeless input as HTTPS; the path stops before the first booru page segment so pasting a post or search URL finds the root.
- `SiteHeaders` customizes only user agent and referer, and values must be single-line so they cannot inject headers.
- `SiteCredentials` live in encrypted storage only; never logged, persisted in clear or backed up.
- `SiteProfile.familyId` stays raw so a family added by a newer app version round-trips; an unknown family keeps the profile but the site is unavailable.
- `clearedChallenge` uses an exhaustive `when` with no `else` so a new probe result must be decided.
- Every custom site uses the underscore tag dialect.

## Consequences

Renaming a host does not change a site's id. New protocol families are added as `ProtocolFamily` entries.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/source/CustomSites.kt`: `CustomSourceIds`, `SiteAddress`, `SiteHeaders`, `SiteCredentials`, `SiteProfile`, `SiteProbeResult`, `clearedChallenge`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/tags/SourceTagNormalization.kt`: `tagDialect`
