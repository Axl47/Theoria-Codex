package com.theoriacodex.app.fixtures

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

class HeavyHistory(media: ImageRef) {
    private val groups = listOf(
        Group("frieren", listOf("fern", "frieren", "blush", "artist_name"), liked = 12, codexOnly = 2, fresh = 4),
        Group("frieren solo", listOf("frieren", "blush"), liked = 3),
        Group("miku", listOf("hatsune_miku", "blush", "artist_name"), liked = 10, fresh = 4),
        Group("marin", listOf("kitagawa_marin", "blush"), liked = 8, fresh = 3),
        Group("everyday", listOf("smile", "artist_name"), liked = 10, fresh = 3),
    )
    private var nextId = 0
    private val fresh = groups.associate { group ->
        group.name to group.posts(group.fresh, media, FRESH_TAG.takeIf { group.name == "frieren" })
    }
    private val liked = groups.associate { it.name to it.posts(it.liked, media) }
    private val codexOnly = groups.associate { it.name to it.posts(it.codexOnly, media) }

    val catalog: List<Post> = interleave(fresh.values) + interleave(liked.values) + interleave(codexOnly.values)

    val likes: List<Post> = listOf("everyday", "marin", "miku", "frieren solo", "frieren").flatMap(liked::getValue)

    val codices: Map<String, List<Post>> = mapOf(
        "Everyday" to liked.getValue("everyday").take(4),
        FRIEREN_CODEX to liked.getValue("frieren").takeLast(6) + codexOnly.getValue("frieren"),
        "Miku corner" to liked.getValue("miku").take(4),
    )

    val savedPosts: Int = (likes + codices.values.flatten()).distinctBy(Post::id).size

    val watched: List<List<String>> = (likes + codices.values.flatten()).distinctBy(Post::id).map(Post::canonicalTags) +
        (0 until BROWSED).map { index ->
            listOfNotNull("blush".takeIf { index % 5 != 0 }, "smile".takeIf { index % 3 == 0 }, "artist_name".takeIf { index % 60 == 0 })
        }

    val freshFrieren: Post = fresh.getValue("frieren").first()

    fun groupOf(postId: PostId): String = groups.first { group ->
        listOf(fresh, liked, codexOnly).any { posts -> posts.getValue(group.name).any { it.id == postId } }
    }.name

    private fun Group.posts(count: Int, media: ImageRef, extraTag: String? = null) = (0 until count).map {
        val id = nextId++
        Post(
            id = PostId(SOURCE, "heavy-$id"), preview = media, full = media, pageUrl = null, width = 320, height = 320,
            canonicalTags = tags + listOfNotNull(extraTag), rawTags = emptyList(), authorName = "Heavy artist",
            createdAtEpochMs = 1_000L + id,
            title = "$name $id",
            creatorProfile = CreatorProfile(SOURCE, "Heavy artist", "heavy-artist", uploadsQuery = "heavy-artist"),
        )
    }

    private class Group(val name: String, val tags: List<String>, val liked: Int, val codexOnly: Int = 0, val fresh: Int = 0)

    companion object {
        val SOURCE = SourceKey.GELBOORU
        const val FRIEREN_CODEX = "Frieren party"

        const val FRESH_TAG = "sketch"
        private const val BROWSED = 300
    }
}

private fun <T> interleave(lists: Collection<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { index -> lists.mapNotNull { it.getOrNull(index) } }
