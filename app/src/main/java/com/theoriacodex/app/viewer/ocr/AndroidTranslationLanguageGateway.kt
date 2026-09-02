package com.theoriacodex.app.viewer.ocr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.ULocale
import android.os.Build
import android.view.translation.TranslationCapability
import android.view.translation.TranslationManager
import android.view.translation.TranslationSpec
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Detects and opens downloads for the device's source-to-English translation resources. */
internal class AndroidTranslationLanguageGateway(
    context: Context,
    private val appContext: Context = context.applicationContext,
) : TranslationLanguageGateway {
    override suspend fun availability(): Map<ViewerOcrLanguage, TranslationLanguageAvailability> =
        withContext(Dispatchers.IO) {
            if (isPackageInstalled(SAMSUNG_TRANSLATION_PACKAGE)) {
                return@withContext ViewerOcrLanguage.entries.associateWith { language ->
                    if (isPackageInstalled(samsungTranslationPackPackage(language))) {
                        TranslationLanguageAvailability.READY
                    } else {
                        TranslationLanguageAvailability.DOWNLOADABLE
                    }
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@withContext unavailableLanguages()
            }
            genericAndroidAvailability()
        }

    override suspend fun requestDownload(language: ViewerOcrLanguage): Boolean =
        withContext(Dispatchers.Main) {
            if (isPackageInstalled(SAMSUNG_TRANSLATION_PACKAGE)) {
                openSamsungLanguagePack(language)
            } else {
                openStandardTranslationSettings()
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun genericAndroidAvailability(): Map<ViewerOcrLanguage, TranslationLanguageAvailability> {
        val manager = appContext.getSystemService(TranslationManager::class.java)
            ?: return unavailableLanguages()
        val capabilities = manager.getOnDeviceTranslationCapabilities(
            TranslationSpec.DATA_FORMAT_TEXT,
            TranslationSpec.DATA_FORMAT_TEXT,
        )
        val hasSettings = manager.onDeviceTranslationSettingsActivityIntent != null
        return ViewerOcrLanguage.entries.associateWith { language ->
            when {
                capabilities.any { capability -> capability.matches(language) } ->
                    TranslationLanguageAvailability.READY
                hasSettings -> TranslationLanguageAvailability.DOWNLOADABLE
                else -> TranslationLanguageAvailability.UNAVAILABLE
            }
        }
    }

    private fun openSamsungLanguagePack(language: ViewerOcrLanguage): Boolean {
        val packageName = samsungTranslationPackPackage(language)
        val storeIntent = Intent(
            Intent.ACTION_VIEW,
            "https://apps.samsung.com/appquery/appDetail.as?appId=$packageName".toUri(),
        ).apply {
            setPackage(SAMSUNG_STORE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (storeIntent.resolveActivity(appContext.packageManager) != null) {
            return runCatching { appContext.startActivity(storeIntent) }.isSuccess
        }
        return openSamsungTranslationSettings(appContext)
    }

    private fun openStandardTranslationSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val intent = appContext.getSystemService(TranslationManager::class.java)
            ?.onDeviceTranslationSettingsActivityIntent
            ?: return false
        return runCatching { intent.send() }.isSuccess
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationInfo(packageName, 0)
            }
            applicationInfo.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun TranslationCapability.matches(language: ViewerOcrLanguage): Boolean {
    return state == TranslationCapability.STATE_ON_DEVICE &&
        sourceSpec.locale.language == language.languageTag() &&
        targetSpec.locale.language == ULocale.ENGLISH.language
}

internal fun samsungTranslationPackPackage(language: ViewerOcrLanguage): String = when (language) {
    ViewerOcrLanguage.JAPANESE -> "$SAMSUNG_LANGUAGE_PACK_PREFIX.enja"
    ViewerOcrLanguage.CHINESE -> "$SAMSUNG_LANGUAGE_PACK_PREFIX.enzh"
    ViewerOcrLanguage.KOREAN -> "$SAMSUNG_LANGUAGE_PACK_PREFIX.enko"
}

private fun ViewerOcrLanguage.languageTag(): String = when (this) {
    ViewerOcrLanguage.JAPANESE -> "ja"
    ViewerOcrLanguage.CHINESE -> "zh"
    ViewerOcrLanguage.KOREAN -> "ko"
}

private fun unavailableLanguages(): Map<ViewerOcrLanguage, TranslationLanguageAvailability> =
    ViewerOcrLanguage.entries.associateWith { TranslationLanguageAvailability.UNAVAILABLE }

internal fun openSamsungTranslationSettings(context: Context): Boolean {
    val intent = Intent(SAMSUNG_TRANSLATION_SETTINGS_ACTION).apply {
        setPackage(SAMSUNG_TRANSLATION_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) == null) return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

private const val SAMSUNG_TRANSLATION_PACKAGE = "com.samsung.android.smartsuggestions"
private const val SAMSUNG_STORE_PACKAGE = "com.sec.android.app.samsungapps"
private const val SAMSUNG_LANGUAGE_PACK_PREFIX =
    "com.samsung.android.nmt.apps.t2t.languagepack"
private const val SAMSUNG_TRANSLATION_SETTINGS_ACTION =
    "com.samsung.android.smartsuggestions.translate.settings.LAUNCH_SETTINGS"
