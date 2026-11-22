package com.theoriacodex.app.journeys

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/**
 * A profile's long history on one source, shaped so every choice made from it has one right answer:
 * - `blush` is on most of what the profile watches and keeps alike, so it never leads a lane or a chip;
 * - `artist_name` is kept far more often than watched, but it is text drawn on the image;
 * - `fern` nearly always appears with `frieren`, and both are kept far more often than watched: one paired lane.
 *   `frieren` is on a few more kept posts, so it ranks above `fern`;
 * - `hatsune_miku` and `kitagawa_marin` are kept far more often than watched: one lane each;
 * - `smile` is kept about as often as it is watched.
 *
 * Kept posts are Liked, and some of them are also in a Codex. Fresh posts are none of these, so the source can still
 * serve them in lanes; the fresh Frieren posts alone also carry `sketch`, for a search that finds only them.
 */
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

    /** Everything the source serves: fresh posts first, so a search shows them on its first page, groups interleaved. */
    val catalog: List<Post> = interleave(fresh.values) + interleave(liked.values) + interleave(codexOnly.values)

    /** The Likes, oldest first; Frieren posts are liked last, so they lead the Likes collection. */
    val likes: List<Post> = listOf("everyday", "marin", "miku", "frieren solo", "frieren").flatMap(liked::getValue)

    /**
     * Each Codex's posts by name, in the order they are created. Most of them are also Liked. The Frieren Codex is
     * neither first nor saved into last, so only relevance can suggest it first.
     */
    val codices: Map<String, List<Post>> = mapOf(
        "Everyday" to liked.getValue("everyday").take(4),
        FRIEREN_CODEX to liked.getValue("frieren").takeLast(6) + codexOnly.getValue("frieren"),
        "Miku corner" to liked.getValue("miku").take(4),
    )

    /** Every saved post once, whether Liked, in a Codex, or both. */
    val savedPosts: Int = (likes + codices.values.flatten()).distinctBy(Post::id).size

    /**
     * The tags of every post the profile watched: each saved post once, then [BROWSED] others, most with `blush`, a
     * third with `smile`, and a few with `artist_name`.
     */
    val watched: List<List<String>> = (likes + codices.values.flatten()).distinctBy(Post::id).map(Post::canonicalTags) +
        (0 until BROWSED).map { index ->
            listOfNotNull("blush".takeIf { index % 5 != 0 }, "smile".takeIf { index % 3 == 0 }, "artist_name".takeIf { index % 60 == 0 })
        }

    /** The fresh post a Frieren fan would save, first in the catalog; it lists `fern` before `frieren`. */
    val freshFrieren: Post = fresh.getValue("frieren").first()

    /** Which group's tags [post] carries. */
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

        /** On the fresh Frieren posts and no other. */
        const val FRESH_TAG = "sketch"
        private const val BROWSED = 300
    }
}

/** The first of every list, then the second of every list, and so on. */
private fun <T> interleave(lists: Collection<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { index -> lists.mapNotNull { it.getOrNull(index) } }
