package com.theoriacodex.app.series

import androidx.compose.ui.state.ToggleableState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName

data class SourceChoice(val key: SourceKey, val language: String)

data class SourceGroup(val name: String, val choices: List<SourceChoice>) {
    val keys: List<SourceKey> get() = choices.map(SourceChoice::key)

    fun state(skipped: Set<SourceKey>): ToggleableState = when (choices.count { it.key !in skipped }) {
        0 -> ToggleableState.Off
        choices.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

fun sourceGroups(sources: List<SeriesSource>): List<SourceGroup> =
    sources.groupBy(SeriesSource::name).map { (name, members) -> SourceGroup(name, members.map { SourceChoice(it.key, languageName(it.language)) }) }

fun Set<SourceKey>.toggled(keys: Collection<SourceKey>): Set<SourceKey> = if (keys.any(::contains)) this - keys.toSet() else this + keys
