package com.theoriacodex.app.series

import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName

/**
 * What the screens call [this] among the sources that are on, [sources]: its name, with its language after it, as the
 * Extensions page writes it, when another source has the same name. One extension is often several sources, one per
 * language, all with the extension's name.
 */
internal fun SeriesSource.label(sources: List<SeriesSource>): String =
    if (sources.any { it.key != key && it.name == name }) "$name · ${languageName(language)}" else name
