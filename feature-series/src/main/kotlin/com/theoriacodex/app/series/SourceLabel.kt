package com.theoriacodex.app.series

import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName

fun SeriesSource.label(sources: List<SeriesSource>): String =
    if (sources.any { it.key != key && it.name == name }) "$name · ${languageName(language)}" else name
