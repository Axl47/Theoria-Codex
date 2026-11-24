# 0065. Source ids are the first eight bytes of MD5 of name/lang/versionId, as Mihon and Aniyomi derive them

Status: Accepted

## Context

Series, covers and user choices store source ids. The ids must be identical across installs and match what Mihon and Aniyomi compute, or stored data stops pointing at its source.

## Decision

- `HttpSource.id` and the Aniyomi equivalent use `sourceId(name, lang, versionId)`: MD5 of `"<lowercased name>/<lang>/<versionId>"`, the first eight digest bytes read big-endian into a Long with the high bit cleared (`and Long.MAX_VALUE`). Reading them as a signed Long would give different ids for stored series, so both steps are part of the derivation (see 0056).
- `SourceIdentity.kt` is host-only code; extensions call the members of the upstream classes that use it.
- `withoutDomain` returns a URL's path, query and fragment as sources store listed addresses, and the input unchanged when it is not a valid URI.

## Consequences

Changing the derivation orphans every stored extension series. Sources that rename themselves get a new id.

## Applies to

- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/online/SourceIdentity.kt`: `sourceId`, `withoutDomain`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt`: `HttpSource.id`
