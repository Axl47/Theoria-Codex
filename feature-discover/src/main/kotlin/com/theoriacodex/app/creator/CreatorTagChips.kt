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
import com.theoriacodex.app.ui.navigation.TrailOutcome
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.launch

internal fun creatorTopTags(creator: CreatorProfile, posts: List<Post>): List<TagCount> {
    val ownTags = listOfNotNull(creator.uploadsQuery, creator.profileId, creator.displayName).mapTo(HashSet(), ::tagIdentity)
    return topTags(posts, CREATOR_TAG_LIMIT, counted = 2..Int.MAX_VALUE, includeTerm = { term ->
        term.isGeneralTag && tagIdentity(term.value) !in ownTags
    })
}

private fun tagIdentity(tag: String): String = tag.trim().lowercase().replace(' ', '_')

@Composable
internal fun CreatorTagChips(creator: CreatorProfile, posts: List<Post>) {
    val opener = LocalTrailOpener.current
    val tags = remember(creator, posts) { creatorTopTags(creator, posts) }
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
                            val outcome = opener.openTrail(TrailRequest.Tag(tag.tag, creator.source, context = null, from = tag.from))
                            if (outcome == TrailOutcome.Empty) empty += tag.tag
                        } finally { busy = null }
                    }
                },
            )
        }
    }
}

private const val CREATOR_TAG_LIMIT = 8
