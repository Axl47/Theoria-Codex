# 0147. Codex share files are bounded on import, never carry device paths, and are exported into per-share folders

Status: Accepted

## Context

Codex JSON files come from other people and from older builds, are parsed from document providers that can block, and are handed to other apps through read grants. Import must tolerate damage without failing the whole file, and exports must not leak device state or later shares.

## Decision

- Export is written with kotlinx.serialization; import walks the JSON tree in `parseCodexShareFile` instead, so a wrong-typed optional value is dropped while the rest of the file still imports. `listOrNull` treats an absent or null list as null and any other non-array as an empty list, so a damaged list never fails the post.
- Device paths never leave the device: `CodexShareImageRef.localPath` uses the kotlinx `@Transient` (kotlinx ignores `kotlin.jvm.Transient`).
- Import limits: byte and depth checks (`CODEX_SHARE_MAX_BYTES`) precede parsing, and per-value bounds (key length, array size, string length, post count) cap work inside otherwise valid documents (`boundedShareValue`).
- Reading a document (`readBoundedTextDocument`) runs on `Dispatchers.IO`, because document providers and exported files can block independently of repository suspension.
- Each share gets its own folder (`writeExportDocument`): a receiver's read grant covers the file, so reusing a path would let an earlier recipient read whatever was shared next. Old shares are removed once no receiver should still need them (limit and 24 h retention).
- `CodexImportViewModel` is one retained import that owns reading, resolution and the atomic commit across UI reattachment. Resolution uses fixed batches of four (`chunked(4)` with `awaitAll`), bounding live requests and coroutine allocation while keeping input order.
- `CodexTransferService.save` commits the selection atomically; optional cache failures cannot truncate durable saves. The `keepOffline` callback hears every committed save, so a Codex kept offline also keeps the posts saved into it.

## Consequences

A file with some bad fields still imports the rest; a file over the limits is rejected before any heavy parsing. Changing the share format means keeping the tolerant tree reader in step with the typed writer.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexShareModels.kt`: `CodexShareFile`, `CodexShareImageRef`, `listOrNull`, `boundedShareValue`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/transfer/CodexDocumentIo.kt`: `readBoundedTextDocument`, `writeExportDocument`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/transfer/CodexImportViewModel.kt`: `CodexImportViewModel`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/transfer/CodexTransferService.kt`: `CodexTransferService`, `save`
