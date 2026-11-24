# 0125. Copies of a post on other sites are grouped in memory from hashes learned this session

Status: Accepted

## Context

A copy of a liked, saved or watched post on another site should count as liked, saved or watched.

## Decision

- `FileMirrorIndex` groups posts known this session that share a file hash or a picture hash; copies of copies form one group. Feeds call `learn`; `BrowsingMetadataViewModel` teaches the stored library, watch history and stored picture hashes. Nothing is persisted.
- `FileCopyLookup.find` adds the post's file on the user's other sites by file hash, and keeps an answer only when its file hash equals the requested one, because some sites ignore the hash term and list unrelated posts.
- A stored post can lack hashes a listing has, so it stays open to a later listing that has them.
- `links` grows when two known posts are first found to be copies, so readers of `withCopies` re-read then.

## Consequences

Copy detection is only as good as what was listed or stored this session.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/post/FileMirrorIndex.kt`: `FileMirrorIndex`, `learn`, `learnPictures`, `links`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/FileCopyLookup.kt`: `FileCopyLookup`, `find`
