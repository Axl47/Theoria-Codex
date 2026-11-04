package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

/** [PostTagActionSection] for screens whose term actions take the post they apply to. */
@Composable
internal fun PostBoundTagSection(
    post: Post,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
) {
    PostTagActionSection(
        post = post,
        tagVideoCountProvider = tagVideoCountProvider,
        fetchTagVideoCounts = fetchTagVideoCounts,
        onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
        onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
        onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
        onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
        onFavoriteTagLongPress = onFavoriteTagLongPress,
    )
}
