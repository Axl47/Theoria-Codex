package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.saveable.listSaver
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey

/** The navigation handoff survives Activity/process recreation without retaining provider objects. */
internal val CreatorProfileStateSaver = listSaver<CreatorProfile?, String>(
    save = { creator -> creator?.let(::encodeCreatorNavigationIdentity).orEmpty() },
    restore = ::decodeCreatorNavigationIdentity,
)

internal fun encodeCreatorNavigationIdentity(creator: CreatorProfile): List<String> = listOf(
    creator.source.name,
    creator.displayName,
    creator.profileId.orEmpty(),
    creator.profileUrl.orEmpty(),
    creator.uploadsQuery.orEmpty(),
)

internal fun decodeCreatorNavigationIdentity(values: List<String>): CreatorProfile? {
    if (values.size != 5) return null
    val source = SourceKey.entries.firstOrNull { it.name == values[0] } ?: return null
    return CreatorProfile(
        source = source,
        displayName = values[1],
        profileId = values[2].takeIf(String::isNotBlank),
        profileUrl = values[3].takeIf(String::isNotBlank),
        uploadsQuery = values[4].takeIf(String::isNotBlank),
    )
}
