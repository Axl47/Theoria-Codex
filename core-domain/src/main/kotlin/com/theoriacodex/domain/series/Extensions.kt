package com.theoriacodex.domain.series

import kotlinx.coroutines.flow.StateFlow

/** What the user decided about extensions; kept in the app's settings store. */
data class ExtensionChoices(
    /**
     * SHA-256 fingerprints (lowercase hex) of the signing certificates the user turned on. An extension runs only
     * while its certificate is here, so an update signed with the same key stays on and a copy signed with another
     * key is off.
     */
    val trustedSigners: Set<String> = emptySet(),
    /** Per extension package, the source languages the user chose; a package not named here uses [defaultLanguages]. */
    val languages: Map<String, Set<String>> = emptyMap(),
)

interface ExtensionChoiceStore {
    suspend fun choices(): ExtensionChoices

    suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices)
}

/** An extension on the device, as the Extensions page shows it. */
data class InstalledExtension(
    /** Names this copy of the extension among those on the device. */
    val id: String,
    val name: String,
    val versionName: String,
    /** Its signing certificate's SHA-256; null when it has none that can be read, and then it can never be turned on. */
    val signer: String?,
    val on: Boolean,
    /** Why it offers no sources although it is on, or why it cannot be turned on. */
    val problem: String? = null,
    /** Every language it has a source for; empty until it is on, because finding them runs its code. */
    val languages: List<ExtensionLanguage> = emptyList(),
)

data class ExtensionLanguage(val code: String, val on: Boolean)

data class ExtensionSourceSettings(val sourceId: String, val sourceName: String, val language: String, val settings: List<ExtensionSetting>)

/** One setting an extension source defines. [summary] already describes the current value where the source asked for that. */
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
        /** What the source wants above the text field; null leaves [title]. */
        val dialogTitle: String? = null,
        val dialogMessage: String? = null,
    ) : ExtensionSetting

    /** One of [options]; [value] is null when nothing is chosen yet. */
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

/**
 * The extensions on the device and the user's choices about them. Listing an extension that is off reads only what
 * the package manager knows and runs none of its code. Every call can take seconds, so call off the main thread;
 * none throws for an extension's own failure, which becomes that extension's [InstalledExtension.problem].
 */
interface SeriesExtensions {
    /** Changes whenever the sources on offer may have: an extension or a language turned on or off. */
    val revision: StateFlow<Int>

    suspend fun installed(): List<InstalledExtension>

    /**
     * Turning one on trusts its signing certificate, and with it every extension signed by the same key. [signer] is
     * the fingerprint the user was shown: when the extension's is another by now, because its file was replaced
     * meanwhile, nothing is turned on and the returned list shows the new one to ask about.
     */
    suspend fun setOn(id: String, signer: String, on: Boolean): List<InstalledExtension>

    suspend fun setLanguage(id: String, language: String, on: Boolean): List<InstalledExtension>

    /** The settings of each of the extension's sources that is on and has any; empty for one that is off. */
    suspend fun settings(id: String): List<ExtensionSourceSettings>

    /** Stores [setting]'s value, unless the source refuses it or is no longer on, and returns [settings] as they now are. */
    suspend fun change(id: String, sourceId: String, setting: ExtensionSetting): List<ExtensionSourceSettings>
}

/** An extension runs only when it has a signing certificate and the user turned that certificate on. */
fun ExtensionChoices.trusts(signer: String?): Boolean = !signer.isNullOrBlank() && signer in trustedSigners

/**
 * The languages of an extension that are on before the user chooses: those among [offered] that one of the device's
 * languages matches, else English, else the first offered. Codes compare by their language part, so `pt-BR` matches a
 * device set to `pt-PT`.
 */
fun defaultLanguages(offered: List<String>, deviceLanguages: List<String>): Set<String> {
    val device = deviceLanguages.mapTo(HashSet(), ::languagePart)
    val matching = offered.filterTo(LinkedHashSet()) { languagePart(it) in device }
    return matching.ifEmpty { setOfNotNull(offered.firstOrNull { languagePart(it) == "en" } ?: offered.firstOrNull()) }
}

private fun languagePart(code: String): String = code.substringBefore('-').substringBefore('_').lowercase()
