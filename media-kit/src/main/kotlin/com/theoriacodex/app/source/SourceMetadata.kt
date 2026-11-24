package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.mediakit.R
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT

enum class SourcePresentationGroup(val folderLabel: String? = null) {
    ILLUSTRATION,
    GALLERY,
    VIDEO,
    RULE34(folderLabel = "R34"),
    DANBOORU(folderLabel = "Booru"),

    CUSTOM(folderLabel = "Custom"),
}

enum class SourceExposure {
    USER_VISIBLE,
    CREDENTIAL_GATED,
}

sealed interface SourceLogoAsset {
    data class Drawable(val resourceId: Int) : SourceLogoAsset

    data class RawSvg(val resourceId: Int) : SourceLogoAsset

    data object Text : SourceLogoAsset

    data class Favicon(val url: String) : SourceLogoAsset
}

data class DurationLookupPolicy(
    val startIntervalMs: Long = 0L,
    val maxConcurrent: Int = 4,
    val notFoundMeansThrottled: Boolean = false,
)

data class SourcePresentation(
    val source: SourceKey,
    val label: String,
    val referer: String,
    val exposure: SourceExposure,
    val group: SourcePresentationGroup,
    val order: Int,
    val logo: SourceLogoAsset,
    val mark: String? = null,
    val userAgent: String = BROWSER_USER_AGENT,
    val cardsStayOnPreview: Boolean = false,
    val durationLookup: DurationLookupPolicy = DurationLookupPolicy(),
) {
    fun isUserExposed(rule34XxxConfigured: Boolean): Boolean {
        return when (exposure) {
            SourceExposure.USER_VISIBLE -> true
            SourceExposure.CREDENTIAL_GATED -> rule34XxxConfigured
        }
    }
}

object SourcePresentationCatalog {
    private val entries = listOf(
        SourcePresentation(
            source = SourceKey.PIXIV,
            label = "Pixiv",
            referer = "https://www.pixiv.net/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.ILLUSTRATION,
            order = 1,
            logo = SourceLogoAsset.Drawable(R.drawable.pixiv_logo),
            durationLookup = DurationLookupPolicy(startIntervalMs = 500L, maxConcurrent = 2),
        ),
        SourcePresentation(
            source = SourceKey.GELBOORU,
            label = "Gelbooru",
            referer = "https://gelbooru.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.ILLUSTRATION,
            order = 0,
            logo = SourceLogoAsset.RawSvg(R.raw.gelbooru_logo),
            durationLookup = DurationLookupPolicy(startIntervalMs = 500L, notFoundMeansThrottled = true),
        ),
        SourcePresentation(
            source = SourceKey.DANBOORU,
            label = "Danbooru",
            referer = "https://danbooru.donmai.us/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.DANBOORU,
            order = 9,
            logo = SourceLogoAsset.Text,
            mark = "DB",
            userAgent = THEORIA_SOURCE_USER_AGENT,
        ),
        SourcePresentation(
            source = SourceKey.AIBOORU,
            label = "AIBooru",
            referer = "https://aibooru.online/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.DANBOORU,
            order = 10,
            logo = SourceLogoAsset.Text,
            mark = "AI",
        ),
        SourcePresentation(
            source = SourceKey.NHENTAI,
            label = "NHentai",
            referer = "https://nhentai.net/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.GALLERY,
            order = 2,
            logo = SourceLogoAsset.RawSvg(R.raw.nhentai_logo),
        ),
        SourcePresentation(
            source = SourceKey.HITOMI,
            label = "Hitomi",
            referer = "https://hitomi.la/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.GALLERY,
            order = 3,
            logo = SourceLogoAsset.Drawable(R.drawable.hitomi_logo),
        ),
        SourcePresentation(
            source = SourceKey.IWARA,
            label = "Iwara",
            referer = "https://www.iwara.tv/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.VIDEO,
            order = 4,
            logo = SourceLogoAsset.Text,
            mark = "I",
        ),
        SourcePresentation(
            source = SourceKey.RULE34XXX,
            label = "R34X",
            referer = "https://rule34.xxx/",
            exposure = SourceExposure.CREDENTIAL_GATED,
            group = SourcePresentationGroup.RULE34,
            order = 5,
            logo = SourceLogoAsset.Text,
            mark = "R34",
            cardsStayOnPreview = true,
        ),
        SourcePresentation(
            source = SourceKey.RULE34PAHEAL,
            label = "R34P",
            referer = "https://rule34.paheal.net/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 6,
            logo = SourceLogoAsset.Text,
            mark = "R34",
        ),
        SourcePresentation(
            source = SourceKey.RULE34VIDEO,
            label = "R34V",
            referer = "https://rule34video.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 7,
            logo = SourceLogoAsset.Text,
            mark = "R34",
        ),
        SourcePresentation(
            source = SourceKey.RULE34GEN,
            label = "R34G",
            referer = "https://rule34gen.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 8,
            logo = SourceLogoAsset.Text,
            mark = "R34",
        ),
    ).sortedBy(SourcePresentation::order)

    private val bySource = entries.associateBy(SourcePresentation::source)

    @Volatile
    private var custom: CustomPresentations = CustomPresentations(emptyList())

    init {
        check(bySource.keys == SourceKey.builtIns.toSet()) {
            "Source presentation catalog must cover every SourceKey exactly once"
        }
        check(entries.map(SourcePresentation::order).distinct().size == entries.size) {
            "Source presentation order values must be unique"
        }
    }

    fun presentation(source: SourceKey): SourcePresentation =
        bySource[source] ?: custom.bySource[source] ?: customSourcePresentation(source, CustomSourceIds.hostLabel(source.id))

    fun orderedPresentations(): List<SourcePresentation> = entries + custom.ordered

    fun orderedSources(): List<SourceKey> = orderedPresentations().map(SourcePresentation::source)

    fun customSources(): List<SourceKey> = custom.ordered.map(SourcePresentation::source)

    fun installCustom(presentations: List<SourcePresentation>) {
        require(presentations.all { it.source.isCustom }) { "Only custom sources can be installed" }
        require(presentations.map(SourcePresentation::source).distinct().size == presentations.size) {
            "Each custom source can be installed once"
        }
        custom = CustomPresentations(presentations)
    }

    private class CustomPresentations(presentations: List<SourcePresentation>) {
        val ordered: List<SourcePresentation> = presentations.sortedWith(
            compareBy<SourcePresentation> { it.order }.thenBy { it.source.id },
        )
        val bySource: Map<SourceKey, SourcePresentation> = ordered.associateBy(SourcePresentation::source)
    }

    fun exposedSources(rule34XxxConfigured: Boolean): Set<SourceKey> {
        return entries
            .asSequence()
            .filter { presentation -> presentation.isUserExposed(rule34XxxConfigured) }
            .map(SourcePresentation::source)
            .toCollection(linkedSetOf())
    }
}

fun SourceKey.presentation(): SourcePresentation = SourcePresentationCatalog.presentation(this)

const val CUSTOM_PRESENTATION_ORDER_START = 100

fun customSourcePresentation(
    source: SourceKey,
    label: String,
    baseUrl: String? = null,
    referer: String? = null,
    userAgent: String? = null,
    order: Int = Int.MAX_VALUE,
    iconUrl: String? = null,
): SourcePresentation {
    require(source.isCustom) { "Built-in sources have catalog entries" }
    return SourcePresentation(
        source = source,
        label = label,
        referer = referer ?: "${baseUrl ?: "https://${CustomSourceIds.hostLabel(source.id)}"}/",
        exposure = SourceExposure.USER_VISIBLE,
        group = SourcePresentationGroup.CUSTOM,
        order = order,
        logo = SourceLogoAsset.Favicon(iconUrl ?: "${baseUrl ?: "https://${CustomSourceIds.hostLabel(source.id)}"}/favicon.ico"),
        mark = label.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "?",
        userAgent = userAgent ?: BROWSER_USER_AGENT,
        durationLookup = DurationLookupPolicy(startIntervalMs = 500L),
    )
}

fun Iterable<SourceKey>.inPresentationOrder(): List<SourceKey> {
    return distinct().sortedWith(
        compareBy<SourceKey> { source -> source.presentation().order }
            .thenBy(SourceKey::id),
    )
}

fun SourceKey.displayName(): String = presentation().label

fun SourceKey.requestHeaders(): Map<String, String> {
    return mapOf(
        "Referer" to referer(),
        "User-Agent" to presentation().userAgent,
    )
}

fun SourceKey.referer(): String = presentation().referer

fun SourceKey.isRule34Family(): Boolean = presentation().group == SourcePresentationGroup.RULE34

fun exposedRealSources(rule34XxxConfigured: Boolean): Set<SourceKey> {
    return SourcePresentationCatalog.exposedSources(rule34XxxConfigured)
}

private const val BROWSER_USER_AGENT = "Mozilla/5.0"
