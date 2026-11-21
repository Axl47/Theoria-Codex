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
import com.theoriacodex.app.tags.TagCount
import com.theoriacodex.app.tags.topTags
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.launch

/**
 * The general tags most of [posts] carry, most common first (ties keep first-seen order). A tag on a single post
 * says nothing about the creator, so only tags on at least two posts count. Each keeps a loaded post carrying it
 * for the Trail to fork from.
 */
internal fun creatorTopTags(posts: List<Post>): List<TagCount> =
    topTags(posts, CREATOR_TAG_LIMIT, counted = 2..Int.MAX_VALUE, includeTerm = SearchTerm::isGeneralTag)

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
        items(tags, key = TagCount::tag) { tag ->
            TagPickerChip(
                label = tag.tag,
                count = null,
                compact = true,
                tapLabel = "Start a trail from ${tag.tag}",
                enabled = busy == null && tag.tag !in empty,
                stateSuffix = "no posts".takeIf { tag.tag in empty },
                onTap = {
                    busy = tag.tag
                    scope.launch {
                        try {
                            val opened = opener.openTrail(TrailRequest.Tag(tag.tag, creator.source, context = null, from = tag.from))
                            if (!opened) empty += tag.tag
                        } finally { busy = null }
                    }
                },
            )
        }
    }
}

private const val CREATOR_TAG_LIMIT = 8
