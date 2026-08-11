package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexAutomaticTagsTest {
    @Test
    fun `presentation moves selected tags into Automatic and preserves counts`() {
        val presentation = codexAutomaticTagPresentation(
            automaticTags = listOf(
                CodexAutomaticTag(SourceKey.PIXIV, "blue sky"),
                CodexAutomaticTag(SourceKey.HITOMI, "legacy"),
            ),
            tagOptionsBySource = mapOf(
                SourceKey.PIXIV to listOf(
                    CodexSearchTagOption("blue_sky", 3),
                    CodexSearchTagOption("portrait", 2),
                ),
                SourceKey.GELBOORU to listOf(CodexSearchTagOption("blue_sky", 1)),
            ),
        )

        assertEquals(
            listOf(
                CodexAutomaticTagRow(CodexAutomaticTag(SourceKey.PIXIV, "blue sky"), 3),
                CodexAutomaticTagRow(CodexAutomaticTag(SourceKey.HITOMI, "legacy"), 0),
            ),
            presentation.automaticRows,
        )
        assertEquals(
            listOf(SourceKey.PIXIV to 0, SourceKey.HITOMI to 0),
            presentation.automaticGroups.map { group -> group.source to group.groupIndex },
        )
        assertEquals(
            listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
            presentation.availableSections.map { section -> section.source },
        )
        assertEquals(
            listOf("blue_sky"),
            presentation.availableSections.first().rows.map { row -> row.automaticTag.tag },
        )
        assertEquals(
            listOf("portrait"),
            presentation.availableSections.last().rows.map { row -> row.automaticTag.tag },
        )
    }
}
