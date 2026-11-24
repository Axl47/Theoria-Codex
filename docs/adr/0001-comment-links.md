# 0001. Comment links use the site's anchor when it has one, else a fragment browsers ignore

Status: Accepted

## Context

A shared comment link should open the right comment in the app and, where possible, in a browser. Comment ids are numeric on every source with comments.

## Decision

- `commentLink` replaces any existing fragment on the page URL. A top-level comment uses the site's native anchor (`nativeAnchor`) when it has one; replies and anchorless sites use `#theoria-comment=<id>` or `#theoria-comment=<parentId>/<id>`, which browsers ignore.
- `parseCommentTarget` reads Theoria's form, Gelbooru's `c<id>` and Danbooru's `comment-<id>` / `comment_<id>`; any other fragment, including non-numeric ids, returns null.
- `CommentHint.count` is exact when the listing gives a number, 0 when it says none, null when it only says some; a post whose listing says nothing carries no hint.

## Consequences

Adding a source with comment anchors means extending `parseCommentTarget` and the adapter's `nativeCommentAnchor`.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/CommentLinks.kt`: `commentLink`, `parseCommentTarget`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Comments.kt`: `CommentHint`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/model/CommentLinksTest.kt`: `top-level comments use the native anchor and replies use Theoria's fragment (ADR 0001)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Comments.kt`: `data class CommentHint(val count: Int?)`
