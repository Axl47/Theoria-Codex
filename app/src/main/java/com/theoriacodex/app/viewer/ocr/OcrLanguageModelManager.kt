package com.theoriacodex.app.viewer.ocr

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface OcrLanguageModelState {
    data object Checking : OcrLanguageModelState
    data object NotDownloaded : OcrLanguageModelState
    data class Downloading(val progressPercent: Int? = null) : OcrLanguageModelState
    data object Ready : OcrLanguageModelState
    data class Failed(val message: String) : OcrLanguageModelState
    data class Unavailable(val message: String) : OcrLanguageModelState
}

interface OcrLanguageModelSource {
    val states: StateFlow<Map<ViewerOcrLanguage, OcrLanguageModelState>>

    suspend fun refresh()
    suspend fun download(language: ViewerOcrLanguage): Boolean
}

internal fun interface CjkTextRecognizerFactory {
    fun create(language: ViewerOcrLanguage): TextRecognizer
}

internal class MlKitCjkTextRecognizerFactory : CjkTextRecognizerFactory {
    override fun create(language: ViewerOcrLanguage): TextRecognizer = when (language) {
        ViewerOcrLanguage.JAPANESE -> TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build(),
        )
        ViewerOcrLanguage.CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
        ViewerOcrLanguage.KOREAN -> TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build(),
        )
    }
}

internal interface OcrLanguageModuleGateway {
    suspend fun isDownloaded(language: ViewerOcrLanguage): Boolean

    suspend fun download(
        language: ViewerOcrLanguage,
        onProgress: (Int?) -> Unit,
    )
}

internal class OcrModulesUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class GooglePlayOcrLanguageModuleGateway(
    context: Context,
    private val recognizerFactory: CjkTextRecognizerFactory,
    private val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(context.applicationContext),
    private val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance(),
    private val appContext: Context = context.applicationContext,
) : OcrLanguageModuleGateway {
    override suspend fun isDownloaded(language: ViewerOcrLanguage): Boolean {
        ensureGooglePlayServices()
        val recognizer = recognizerFactory.create(language)
        return try {
            moduleInstallClient.areModulesAvailable(recognizer).await().areModulesAvailable()
        } finally {
            recognizer.close()
        }
    }

    override suspend fun download(
        language: ViewerOcrLanguage,
        onProgress: (Int?) -> Unit,
    ) {
        ensureGooglePlayServices()
        val recognizer = recognizerFactory.create(language)
        val completion = CompletableDeferred<Unit>()
        val listener = InstallStatusListener { update ->
            update.progressInfo?.let { progress ->
                val percent = (
                    progress.bytesDownloaded * PERCENT_SCALE / progress.totalBytesToDownload
                ).toInt().coerceIn(0, PERCENT_SCALE)
                onProgress(percent)
            }
            when (update.installState) {
                STATE_COMPLETED -> completion.complete(Unit)
                STATE_CANCELED -> completion.completeExceptionally(
                    IllegalStateException("OCR model download was canceled"),
                )
                STATE_FAILED -> completion.completeExceptionally(
                    moduleInstallFailure(update.errorCode),
                )
            }
        }
        try {
            val request = ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .setListener(listener)
                .build()
            val response = moduleInstallClient.installModules(request).await()
            if (response.areModulesAlreadyInstalled()) {
                completion.complete(Unit)
            }
            completion.await()
        } finally {
            withContext(NonCancellable) {
                try {
                    moduleInstallClient.unregisterListener(listener).await()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The terminal install state is already known; listener cleanup is best effort.
                }
            }
            recognizer.close()
        }
    }

    private fun ensureGooglePlayServices() {
        if (googleApiAvailability.isGooglePlayServicesAvailable(appContext) != ConnectionResult.SUCCESS) {
            throw OcrModulesUnavailableException(
                "Downloadable OCR requires compatible Google Play services",
            )
        }
    }
}

internal class DefaultOcrLanguageModelManager(
    private val gateway: OcrLanguageModuleGateway,
) : OcrLanguageModelSource {
    private val mutableStates: MutableStateFlow<Map<ViewerOcrLanguage, OcrLanguageModelState>> =
        MutableStateFlow(
            ViewerOcrLanguage.entries.associate { language ->
                language to OcrLanguageModelState.Checking
            },
        )
    override val states: StateFlow<Map<ViewerOcrLanguage, OcrLanguageModelState>> =
        mutableStates.asStateFlow()
    private val languageLocks = ViewerOcrLanguage.entries.associateWith { Mutex() }

    override suspend fun refresh() {
        kotlinx.coroutines.coroutineScope {
            ViewerOcrLanguage.entries.map { language ->
                async {
                    language to requireNotNull(languageLocks[language]).withLock {
                        queryState(language)
                    }
                }
            }.awaitAll().forEach { (language, state) -> setState(language, state) }
        }
    }

    override suspend fun download(language: ViewerOcrLanguage): Boolean {
        return requireNotNull(languageLocks[language]).withLock {
            if (states.value[language] == OcrLanguageModelState.Ready) return@withLock true
            setState(language, OcrLanguageModelState.Downloading())
            try {
                gateway.download(language) { progress ->
                    setState(language, OcrLanguageModelState.Downloading(progress))
                }
                val state = queryState(language)
                setState(language, state)
                state == OcrLanguageModelState.Ready
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: OcrModulesUnavailableException) {
                setState(
                    language,
                    OcrLanguageModelState.Unavailable(unavailable.message.orEmpty()),
                )
                false
            } catch (failure: Exception) {
                setState(
                    language,
                    OcrLanguageModelState.Failed(
                        failure.message?.takeIf(String::isNotBlank) ?: "OCR model download failed",
                    ),
                )
                false
            }
        }
    }

    private suspend fun queryState(language: ViewerOcrLanguage): OcrLanguageModelState {
        return try {
            if (gateway.isDownloaded(language)) {
                OcrLanguageModelState.Ready
            } else {
                OcrLanguageModelState.NotDownloaded
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unavailable: OcrModulesUnavailableException) {
            OcrLanguageModelState.Unavailable(unavailable.message.orEmpty())
        } catch (failure: Exception) {
            OcrLanguageModelState.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: "Could not check OCR model",
            )
        }
    }

    private fun setState(language: ViewerOcrLanguage, state: OcrLanguageModelState) {
        mutableStates.update { current -> current + (language to state) }
    }
}

private fun moduleInstallFailure(errorCode: Int): Exception {
    return when (errorCode) {
        ModuleInstallStatusCodes.INSUFFICIENT_STORAGE ->
            IllegalStateException("Not enough storage for OCR model")
        ModuleInstallStatusCodes.METERED_NETWORK_NOT_ALLOWED ->
            IllegalStateException("OCR model download requires an unmetered network")
        ModuleInstallStatusCodes.MODULE_NOT_FOUND,
        ModuleInstallStatusCodes.NOT_ALLOWED_MODULE,
        ModuleInstallStatusCodes.UNKNOWN_MODULE,
        -> OcrModulesUnavailableException("OCR model is unavailable on this device")
        else -> IllegalStateException("OCR model download failed")
    }
}

private const val PERCENT_SCALE = 100
