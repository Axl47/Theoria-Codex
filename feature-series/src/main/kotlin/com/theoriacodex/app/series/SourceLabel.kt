package com.theoriacodex.app.series

import com.theoriacodex.domain.series.SeriesSource
import java.util.Locale

/**
 * What the screens call [this] among the sources that are on, [sources]: its name, with its language after it, as the
 * Extensions page writes it, when another source has the same name. One extension is often several sources, one per
 * language, all with the extension's name.
 */
internal fun SeriesSource.label(sources: List<SeriesSource>): String =
    if (sources.any { it.key != key && it.name == name }) "$name · ${languageName(language)}" else name

/** A source language's name in the device's language; Mihon's two codes that are not languages get plain words. */
internal fun languageName(code: String): String = when (code) {
    "all" -> "All"
    "other" -> "Other"
    else -> Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
}
