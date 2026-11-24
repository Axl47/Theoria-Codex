# 0033. All saved data uses one kotlinx Json configuration that keeps Gson-era behaviour

Status: Accepted

## Context

Saved data was written by Gson before kotlinx.serialization replaced it. Older builds, backups and hand-edited files must keep loading, and older builds must read what the current one writes.

## Decision

- `DurableJson` is the shared configuration for files, Room payloads, exports, and fixed-shape network, asset and developer-copy JSON; the exception is series backup memos, which are checked as JSON objects with plain `Json` (`storedMemo`). Each flag preserves Gson behaviour:
  - `ignoreUnknownKeys`: newer files and retired fields are not rejected.
  - `encodeDefaults`: backup validation requires defaulted keys to be present, as Gson wrote them.
  - `explicitNulls = false`: Gson omits nulls, and some readers treat a present key as data.
  - `coerceInputValues`: a stored null on a defaulted field repairs to the default instead of resetting the store.
  - `isLenient`: Gson accepted quoted primitives such as "5" for an Int and "true" for a Boolean.
- `decodeEachOrSkip` drops list elements whose decoding throws `SerializationException`, so one malformed entry never hides well-formed neighbours; any other exception propagates.
- `storedVersion` reads a top-level integer version without decoding the rest, so the DataStore-backed stores refuse a newer build's file whatever its shape.

## Consequences

Changing a flag changes how every stored file loads; the golden-file tests pin the effect.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DurableJson.kt`: `DurableJson`, `decodeEachOrSkip`, `storedVersion`
