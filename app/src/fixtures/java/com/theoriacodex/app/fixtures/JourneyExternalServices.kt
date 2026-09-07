package com.theoriacodex.app.fixtures

import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.sourceauth.InMemorySourceCredentialsStore
import com.theoriacodex.app.sourceauth.SourceAccountStore
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelSource
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelState
import com.theoriacodex.app.viewer.ocr.ViewerOcrAnalysis
import com.theoriacodex.app.viewer.ocr.ViewerOcrAnalysisRequest
import com.theoriacodex.app.viewer.ocr.ViewerOcrTranslationService
import com.theoriacodex.app.viewer.ocr.ViewerTranslationStage
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.flow.MutableStateFlow

internal class JourneyAccounts(sources: Set<SourceKey>) : SourceAccountStore,
    SourceCredentialsProvider by InMemorySourceCredentialsStore() {
    override val recoveryState = MutableStateFlow<CredentialStoreRecoveryState>(CredentialStoreRecoveryState.Ready)
    override val availableSources = MutableStateFlow(sources)
    override suspend fun refreshAvailability() = Unit
    override suspend fun resetAfterReconnectRequired() = true
}

/** Every unconfigured external request fails locally, before opening a socket. */
object JourneyNoNetworkHttpClient : SourceHttpClient {
    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected external request in offline fixture")
    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected external request in offline fixture")
}

internal object JourneyOcrModels : OcrLanguageModelSource {
    override val states = MutableStateFlow<Map<ViewerOcrLanguage, OcrLanguageModelState>>(
        ViewerOcrLanguage.entries.associateWith { OcrLanguageModelState.NotDownloaded },
    )
    override suspend fun refresh() = Unit
    override suspend fun download(language: ViewerOcrLanguage): Boolean = error("Fixture cannot download OCR models")
}

internal object JourneyNoOcr : ViewerOcrTranslationService {
    override suspend fun analyze(request: ViewerOcrAnalysisRequest): ViewerOcrAnalysis? = error("OCR is disabled in navigation fixtures")
    override suspend fun translate(regions: List<ViewerOcrRegion>, onStage: (ViewerTranslationStage) -> Unit): Map<String, String> =
        error("Translation is disabled in navigation fixtures")
}
