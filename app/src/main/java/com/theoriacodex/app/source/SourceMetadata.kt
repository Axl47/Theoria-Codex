package com.theoriacodex.app.source

import com.theoriacodex.app.R
import com.theoriacodex.domain.model.SourceKey

/** Product-facing grouping used to keep source ordering and presentation out of route code. */
enum class SourcePresentationGroup {
    ILLUSTRATION,
    GALLERY,
    VIDEO,
    RULE34,
}

/**
 * Whether a live adapter is exposed as a user-selectable source.
 *
 * [ADAPTER_ONLY] keeps compatibility for saved/imported posts and diagnostics without advertising
 * the provider as a live app feature. AIBooru deliberately remains in that state until it has a
 * product-owned enablement decision and route coverage.
 */
enum class SourceExposure {
    USER_VISIBLE,
    CREDENTIAL_GATED,
    ADAPTER_ONLY,
}

sealed interface SourceLogoAsset {
    data class Drawable(val resourceId: Int) : SourceLogoAsset

    data class RawSvg(val resourceId: Int) : SourceLogoAsset

    data object Text : SourceLogoAsset
}

data class SourcePresentation(
    val source: SourceKey,
    val label: String,
    val referer: String,
    val exposure: SourceExposure,
    val group: SourcePresentationGroup,
    val order: Int,
    val logo: SourceLogoAsset,
) {
    fun isUserExposed(rule34XxxConfigured: Boolean): Boolean {
        return when (exposure) {
            SourceExposure.USER_VISIBLE -> true
            SourceExposure.CREDENTIAL_GATED -> rule34XxxConfigured
            SourceExposure.ADAPTER_ONLY -> false
        }
    }
}

/** The single app-layer catalog for labels, headers, exposure, ordering, grouping, and logos. */
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
        ),
        SourcePresentation(
            source = SourceKey.GELBOORU,
            label = "Gelbooru",
            referer = "https://gelbooru.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.ILLUSTRATION,
            order = 0,
            logo = SourceLogoAsset.RawSvg(R.raw.gelbooru_logo),
        ),
        SourcePresentation(
            source = SourceKey.AIBOORU,
            label = "AIBooru",
            referer = "https://aibooru.online/",
            exposure = SourceExposure.ADAPTER_ONLY,
            group = SourcePresentationGroup.ILLUSTRATION,
            order = 9,
            logo = SourceLogoAsset.Text,
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
        ),
        SourcePresentation(
            source = SourceKey.RULE34XXX,
            label = "R34X",
            referer = "https://rule34.xxx/",
            exposure = SourceExposure.CREDENTIAL_GATED,
            group = SourcePresentationGroup.RULE34,
            order = 5,
            logo = SourceLogoAsset.Text,
        ),
        SourcePresentation(
            source = SourceKey.RULE34PAHEAL,
            label = "R34P",
            referer = "https://rule34.paheal.net/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 6,
            logo = SourceLogoAsset.Text,
        ),
        SourcePresentation(
            source = SourceKey.RULE34VIDEO,
            label = "R34V",
            referer = "https://rule34video.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 7,
            logo = SourceLogoAsset.Text,
        ),
        SourcePresentation(
            source = SourceKey.RULE34GEN,
            label = "R34G",
            referer = "https://rule34gen.com/",
            exposure = SourceExposure.USER_VISIBLE,
            group = SourcePresentationGroup.RULE34,
            order = 8,
            logo = SourceLogoAsset.Text,
        ),
    ).sortedBy(SourcePresentation::order)

    private val bySource = entries.associateBy(SourcePresentation::source)

    init {
        check(bySource.keys == SourceKey.entries.toSet()) {
            "Source presentation catalog must cover every SourceKey exactly once"
        }
        check(entries.map(SourcePresentation::order).distinct().size == entries.size) {
            "Source presentation order values must be unique"
        }
    }

    fun presentation(source: SourceKey): SourcePresentation = bySource.getValue(source)

    fun orderedPresentations(): List<SourcePresentation> = entries

    fun exposedSources(rule34XxxConfigured: Boolean): Set<SourceKey> {
        return entries
            .asSequence()
            .filter { presentation -> presentation.isUserExposed(rule34XxxConfigured) }
            .map(SourcePresentation::source)
            .toCollection(linkedSetOf())
    }
}

fun SourceKey.presentation(): SourcePresentation = SourcePresentationCatalog.presentation(this)

fun Iterable<SourceKey>.inPresentationOrder(): List<SourceKey> {
    return distinct().sortedWith(
        compareBy<SourceKey> { source -> source.presentation().order }
            .thenBy(SourceKey::name),
    )
}

fun SourceKey.displayName(): String = presentation().label

fun SourceKey.requestHeaders(): Map<String, String> {
    return mapOf(
        "Referer" to referer(),
        "User-Agent" to "Mozilla/5.0",
    )
}

fun SourceKey.referer(): String = presentation().referer

fun SourceKey.isRule34Family(): Boolean = presentation().group == SourcePresentationGroup.RULE34

fun exposedRealSources(rule34XxxConfigured: Boolean): Set<SourceKey> {
    return SourcePresentationCatalog.exposedSources(rule34XxxConfigured)
}
