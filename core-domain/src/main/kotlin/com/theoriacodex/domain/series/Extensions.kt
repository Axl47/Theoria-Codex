package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

data class ExtensionChoices(
    val trusted: Map<String, String> = emptyMap(),
    val languages: Map<String, Set<String>> = emptyMap(),
    val owners: Map<String, String> = emptyMap(),
    val repos: List<ExtensionRepo> = emptyList(),
)

interface ExtensionChoiceStore {
    suspend fun choices(): ExtensionChoices

    suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices)
}

data class InstalledExtension(
    val id: String,
    val name: String,
    val versionName: String,
    val signer: String?,
    val on: Boolean,
    val problem: String? = null,
    val languages: List<ExtensionLanguage> = emptyList(),
    val kind: SeriesKind = SeriesKind.MANGA,
    val removable: Boolean = false,
    val icon: ApkIcon? = null,
)

data class ApkIcon(val packageName: String, val versionCode: Long, val apk: String)

data class ExtensionLanguage(val code: String, val on: Boolean)

data class SourceOff(val extension: String, val language: String? = null)

data class SourceAvailability(val on: Set<SourceKey> = emptySet(), val off: Map<SourceKey, SourceOff> = emptyMap())

data class ExtensionSourceSettings(val sourceId: String, val sourceName: String, val language: String, val settings: List<ExtensionSetting>)

sealed interface ExtensionSetting {
    val key: String
    val title: String
    val summary: String?
    val enabled: Boolean

    data class Toggle(override val key: String, override val title: String, override val summary: String?, override val enabled: Boolean, val on: Boolean) :
        ExtensionSetting

    data class Text(
        override val key: String,
        override val title: String,
        override val summary: String?,
        override val enabled: Boolean,
        val value: String,
        val dialogTitle: String? = null,
        val dialogMessage: String? = null,
    ) : ExtensionSetting

    data class Choice(
        override val key: String,
        override val title: String,
        override val summary: String?,
        override val enabled: Boolean,
        val value: String?,
        val options: List<ExtensionSettingOption>,
    ) : ExtensionSetting

    data class MultiChoice(
        override val key: String,
        override val title: String,
        override val summary: String?,
        override val enabled: Boolean,
        val values: Set<String>,
        val options: List<ExtensionSettingOption>,
    ) : ExtensionSetting
}

data class ExtensionSettingOption(val value: String, val label: String)

interface SeriesExtensions {
    val revision: StateFlow<Int>

    suspend fun installed(): List<InstalledExtension>

    suspend fun setOn(id: String, signer: String, on: Boolean): List<InstalledExtension>

    suspend fun setLanguage(id: String, language: String, on: Boolean): List<InstalledExtension>

    suspend fun settings(id: String): List<ExtensionSourceSettings>

    suspend fun change(id: String, sourceId: String, setting: ExtensionSetting): List<ExtensionSourceSettings>
}

fun ExtensionChoices.trusts(extension: String, signer: String?): Boolean = !signer.isNullOrBlank() && trusted[extension] == signer

fun defaultLanguages(offered: List<String>, deviceLanguages: List<String>): Set<String> {
    val device = deviceLanguages.mapTo(HashSet(), ::languagePart)
    val matching = offered.filterTo(LinkedHashSet()) { languagePart(it) in device }
    return matching.ifEmpty { setOfNotNull(offered.firstOrNull { languagePart(it) == "en" } ?: offered.firstOrNull()) }
}

fun languagePart(code: String): String = code.substringBefore('-').substringBefore('_').lowercase()

fun languageName(code: String): String = when (code) {
    "all" -> "All"
    "other" -> "Other"
    else -> Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
}
