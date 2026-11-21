package com.theoriacodex.app.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.tags.TagPickerChip
import com.theoriacodex.app.tags.postActionTerms
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.launch

/** One of a creator's most common tags, with a loaded post carrying it for the Trail to fork from. */
internal data class CreatorTag(val name: String, val count: Int, val from: Post)

/**
 * The general tags most of [posts] carry, most common first (ties keep first-seen order). A tag on a single post
 * says nothing about the creator, so only tags on at least two posts count.
 */
internal fun creatorTopTags(posts: List<Post>, limit: Int = CREATOR_TAG_LIMIT): List<CreatorTag> {
    val counts = linkedMapOf<String, CreatorTag>()
    posts.forEach { post ->
        postActionTerms(post).filter(SearchTerm::isGeneralTag).map(SearchTerm::value).distinct().forEach { tag ->
            counts[tag] = counts[tag]?.let { it.copy(count = it.count + 1) } ?: CreatorTag(tag, 1, post)
        }
    }
    return counts.values.filter { it.count >= 2 }.sortedByDescending(CreatorTag::count).take(limit)
}

/** The creator's common tags across the posts loaded so far; a tap opens a Trail of that tag. */
@Composable
internal fun CreatorTagChips(creator: CreatorProfile, posts: List<Post>) {
    val opener = LocalTrailOpener.current
    val tags = remember(posts) { creatorTopTags(posts) }
    if (opener == TrailOpener.None || tags.isEmpty()) return
    val scope = rememberCoroutineScope()
    var busy by remember(creator) { mutableStateOf<String?>(null) }
    val empty = remember(creator) { mutableStateListOf<String>() }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tags, key = CreatorTag::name) { tag ->
            TagPickerChip(
                label = tag.name,
                count = null,
                compact = true,
                tapLabel = "Start a trail from ${tag.name}",
                enabled = busy == null && tag.name !in empty,
                stateSuffix = "no posts".takeIf { tag.name in empty },
                onTap = {
                    busy = tag.name
                    scope.launch {
                        try {
                            val opened = opener.openTrail(TrailRequest.Tag(tag.name, creator.source, context = null, from = tag.from))
                            if (!opened) empty += tag.name
                        } finally { busy = null }
                    }
                },
            )
        }
    }
}

private const val CREATOR_TAG_LIMIT = 8
