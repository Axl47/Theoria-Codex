package com.theoriacodex.mihonhost

import com.theoriacodex.domain.adapter.Page as ResultPage
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Base64
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Presents one Mihon extension source as a Theoria source. Mihon's unit is a series with a growing chapter list;
 * Theoria's is a post. This spike maps each series to one post whose pages are the series' first chapter, which is
 * enough to exercise an extension end to end (listing, details, chapter list, page list, images) without a series
 * model.
 *
 * Posts carry no tags: a series' genres are not booru tags, and the app treats every tag of a `custom_` source as one.
 */
class MihonSourceAdapter(
    override val sourceKey: SourceKey,
    private val source: Source,
    private val images: ExtensionImages,
    private val log: (String) -> Unit = {},
) : SourceAdapter {
    /** Series as the source listed them. Sources may keep data on them between calls, so a lookup reuses the listing's. */
    private val listed = Collections.synchronizedMap(
        object : LinkedHashMap<String, SManga>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, SManga>): Boolean = size > MAX_LISTED
        },
    )

    init {
        if (source is HttpSource) images.attach(source)
    }

    override val capabilities = SourceCapabilities(
        supportsSortNewest = source.supportsLatest,
        supportsSortPopular = true,
        supportsSortTop = false,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
    )

    /** No terms lists the source's latest or popular series; terms become one free-text search, as Mihon's search box is. */
    override suspend fun search(query: Query, pageToken: String?): ResultPage<Post> = calling {
        val page = pageToken?.toIntOrNull() ?: 1
        // The app spells multi-word terms of a custom source with underscores.
        val text = query.includeTags.joinToString(" ") { it.replace('_', ' ') }.trim()
        val result = when {
            text.isNotEmpty() -> source.getSearchManga(page, text, source.getFilterList())
            query.sort == SortMode.NEWEST && source.supportsLatest -> source.getLatestUpdates(page)
            else -> source.getPopularManga(page)
        }
        ResultPage(result.mangas.map(::listedPost), if (result.hasNextPage) (page + 1).toString() else null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

    /** The series' first chapter as a gallery, or null when the source lists no chapter for it. */
    override suspend fun resolvePost(id: PostId): Post? = calling {
        val started = TimeSource.Monotonic.markNow()
        val requestsBefore = MihonHost.requestCount.get()
        val known = listed[id.sourcePostId]
        val manga = known ?: SManga.create().apply {
            url = mangaUrl(id)
            title = ""
        }
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        // Sources list chapters newest first.
        val chapter = update.chapters.lastOrNull()
        val pages = chapter?.let { source.getPageList(it) }.orEmpty()
        val media = pages.mapNotNull { page -> pageImage(page) }
        log(
            "resolve ${source.name}: chapters=${update.chapters.size} pages=${media.size} " +
                "requests=${MihonHost.requestCount.get() - requestsBefore} ms=${started.elapsedNow().inWholeMilliseconds} " +
                "listingReused=${known != null}",
        )
        if (chapter == null || media.isEmpty()) return@calling null
        val series = manga.withDetails(update.manga)
        listedPost(series, id).copy(
            full = media.first(),
            media = media,
            title = listOf(series.title, chapter.name).filter(String::isNotBlank).joinToString(" · "),
            mediaCount = media.size,
            galleryLengthUnknown = false,
        )
    }

    /**
     * This series with what its details page added. A source's details often leave out the address, the title or the
     * cover it already listed, so, as Mihon does, they only add to the series the app asked about.
     */
    private fun SManga.withDetails(details: SManga): SManga = copy().also { series ->
        // The title has no value until the source sets one.
        runCatching { details.title }.getOrNull()?.takeIf(String::isNotBlank)?.let { series.title = it }
        details.thumbnail_url?.takeIf(String::isNotBlank)?.let { series.thumbnail_url = it }
        details.author?.takeIf(String::isNotBlank)?.let { series.author = it }
    }

    private fun listedPost(manga: SManga): Post {
        val id = PostId(sourceKey, Base64.getUrlEncoder().withoutPadding().encodeToString(manga.url.toByteArray()))
        listed[id.sourcePostId] = manga
        return listedPost(manga, id)
    }

    private fun listedPost(manga: SManga, id: PostId) = Post(
        id = id,
        preview = ImageRef(url = cover(manga), localPath = null, mime = null),
        full = null,
        pageUrl = (source as? HttpSource)?.let { runCatching { it.getMangaUrl(manga) }.getOrNull() },
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = manga.author?.takeIf(String::isNotBlank),
        createdAtEpochMs = null,
        title = manga.title,
        // The pages arrive with the lookup, which is what tells the Viewer to resolve the post on open.
        galleryLengthUnknown = true,
    )

    private fun cover(manga: SManga): String? {
        val address = manga.thumbnail_url?.takeIf(String::isNotBlank) ?: return null
        return if (source is HttpSource) images.coverAddress(source, address) else address
    }

    /** Some sources name each image only on a page of its own; those cost one more request per page here. */
    private suspend fun pageImage(page: Page): ImageRef? {
        if (source !is HttpSource) return page.imageUrl?.let { ImageRef(url = it, localPath = null, mime = null) }
        if (page.imageUrl == null) page.imageUrl = source.getImageUrl(page)
        return ImageRef(url = images.pageAddress(source, page), localPath = null, mime = null)
    }

    /** Reports an extension's failures the way built-in sources report theirs. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> calling(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpException) {
        val reason = when (error.code) {
            HTTP_TOO_MANY_REQUESTS -> SourceFailureReason.RATE_LIMITED
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SourceFailureReason.AUTH_REQUIRED
            else -> SourceFailureReason.NETWORK
        }
        throw SourceAdapterException(reason, error.message, error)
    } catch (error: IOException) {
        throw SourceAdapterException(SourceFailureReason.NETWORK, error.message, error)
    } catch (error: LinkageError) {
        // The extension called a part of Mihon's host API this module does not provide.
        log("${source.name} needs host API this module lacks: $error")
        throw SourceAdapterException(SourceFailureReason.UNKNOWN, error.toString(), error)
    } catch (error: Exception) {
        throw SourceAdapterException(SourceFailureReason.PARSE, error.toString(), error)
    }

    companion object {
        /**
         * The app-wide identity of an extension source. It reuses the Custom Sites id grammar so stored ids parse
         * without the extension loaded; the `mihon` label can never be a real site's host.
         */
        fun sourceKey(source: Source): SourceKey = SourceKey.parseOrThrow("custom_mihon_${source.id}")

        /** The series address a post id carries; it is encoded because addresses contain `/`. */
        internal fun mangaUrl(id: PostId): String = String(Base64.getUrlDecoder().decode(id.sourcePostId))

        private const val MAX_LISTED = 2_048
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
