package com.theoriacodex.app.viewer.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ViewerOcrSelectionIdentity(
    val session: ViewerSessionIdentity,
    val mediaKey: ViewerMediaKey,
    val loadGeneration: Long,
)

internal data class ViewerOcrConfiguration(
    val automaticDetectionEnabled: Boolean,
    val enabledLanguages: Set<ViewerOcrLanguage>,
    val readyLanguages: Set<ViewerOcrLanguage>,
) {
    val eligibleLanguages: Set<ViewerOcrLanguage>
        get() = enabledLanguages intersect readyLanguages
}

internal data class ViewerOcrImageReady(
    val identity: ViewerOcrSelectionIdentity,
    val source: SourceKey,
    val location: String,
    val taxonomy: List<PostTaxonomyTerm>,
)

internal enum class ViewerOcrAnalysisStatus {
    IDLE,
    RECOGNIZING,
    DETECTED,
    NO_TEXT,
    FAILED,
}

internal sealed interface ViewerTranslationCardState {
    val regionId: String

    data class PreparingTranslator(override val regionId: String) : ViewerTranslationCardState
    data class Translating(override val regionId: String) : ViewerTranslationCardState
    data class Ready(
        override val regionId: String,
        val translatedText: String,
    ) : ViewerTranslationCardState
    data class Failed(
        override val regionId: String,
        val message: String,
    ) : ViewerTranslationCardState
}

internal data class ViewerOcrTranslationUiState(
    val identity: ViewerOcrSelectionIdentity? = null,
    val analysisStatus: ViewerOcrAnalysisStatus = ViewerOcrAnalysisStatus.IDLE,
    val language: ViewerOcrLanguage? = null,
    val regions: List<ViewerOcrRegion> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val translationCard: ViewerTranslationCardState? = null,
)

internal class ViewerOcrTranslationViewModel(
    private val service: ViewerOcrTranslationService,
    private val workScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = workScope ?: viewModelScope
    private val mutableState = MutableStateFlow(ViewerOcrTranslationUiState())
    val state: StateFlow<ViewerOcrTranslationUiState> = mutableState.asStateFlow()

    private var configuration = ViewerOcrConfiguration(false, emptySet(), emptySet())
    private var activeInput: ViewerOcrImageReady? = null
    private var analysisJob: Job? = null
    private var translationJob: Job? = null
    private val analysisCache = BoundedAccessCache<ViewerOcrAnalysisCacheKey, ViewerOcrAnalysis?>(
        MAX_ANALYSIS_CACHE_ENTRIES,
    )
    private val translationCache = BoundedAccessCache<ViewerTranslationCacheKey, String>(
        MAX_TRANSLATION_CACHE_ENTRIES,
    )
    private val attemptedLocations = mutableMapOf<ViewerOcrSelectionIdentity, LinkedHashSet<String>>()

    fun synchronize(
        identity: ViewerOcrSelectionIdentity?,
        nextConfiguration: ViewerOcrConfiguration,
    ) {
        val selectionChanged = identity != mutableState.value.identity
        val configurationChanged = nextConfiguration != configuration
        configuration = nextConfiguration
        if (selectionChanged) {
            cancelCurrentWork()
            attemptedLocations.clear()
            activeInput = null
            mutableState.value = ViewerOcrTranslationUiState(identity = identity)
        } else if (configurationChanged) {
            cancelCurrentWork()
            identity?.let(attemptedLocations::remove)
            mutableState.value = ViewerOcrTranslationUiState(identity = identity)
        }
        if (!isEnabled(identity)) {
            cancelCurrentWork()
            mutableState.value = ViewerOcrTranslationUiState(identity = identity)
            return
        }
        activeInput?.takeIf { input -> input.identity == identity }?.let(::analyze)
    }

    fun onImageReady(input: ViewerOcrImageReady) {
        if (input.identity != mutableState.value.identity) return
        val current = mutableState.value
        val previousInput = activeInput
        activeInput = input
        if (!isEnabled(input.identity)) return
        if (
            current.analysisStatus == ViewerOcrAnalysisStatus.DETECTED &&
            activeInput?.identity == input.identity
        ) {
            return
        }
        if (previousInput == input && current.analysisStatus != ViewerOcrAnalysisStatus.FAILED) return
        analyze(input)
    }

    fun onRegionTapped(regionId: String) {
        val snapshot = mutableState.value
        val identity = snapshot.identity ?: return
        val region = snapshot.regions.firstOrNull { candidate -> candidate.id == regionId } ?: return
        val currentCard = snapshot.translationCard
        if (
            currentCard?.regionId == regionId &&
            (currentCard is ViewerTranslationCardState.PreparingTranslator ||
                currentCard is ViewerTranslationCardState.Translating)
        ) {
            return
        }
        translationJob?.cancel()
        val cacheKey = ViewerTranslationCacheKey(region.language, region.sourceText)
        val cached = translationCache[cacheKey]
        if (cached != null) {
            publishCard(identity, ViewerTranslationCardState.Ready(regionId, cached))
            scheduleCardDismiss(identity, regionId, TRANSLATION_SUCCESS_DURATION_MS)
            return
        }
        translationJob = scope.launch {
            publishCard(identity, ViewerTranslationCardState.PreparingTranslator(regionId))
            val result = runCatchingPreservingCancellation {
                service.translate(region.language, region.sourceText) { stage ->
                    if (stage == ViewerTranslationStage.TRANSLATING) {
                        publishCard(identity, ViewerTranslationCardState.Translating(regionId))
                    }
                }
            }
            result.onSuccess { translated ->
                translationCache[cacheKey] = translated
                publishCard(identity, ViewerTranslationCardState.Ready(regionId, translated))
                delay(TRANSLATION_SUCCESS_DURATION_MS)
                dismissCard(identity, regionId)
            }.onFailure { failure ->
                publishCard(
                    identity,
                    ViewerTranslationCardState.Failed(
                        regionId = regionId,
                        message = failure.message?.takeIf(String::isNotBlank)
                            ?: "Couldn't translate · Tap the phrase to retry",
                    ),
                )
                delay(TRANSLATION_FAILURE_DURATION_MS)
                dismissCard(identity, regionId)
            }
        }
    }

    private fun analyze(input: ViewerOcrImageReady) {
        if (!isEnabled(input.identity)) return
        val cacheKey = ViewerOcrAnalysisCacheKey(
            identity = input.identity,
            location = input.location,
            eligibleLanguages = configuration.eligibleLanguages,
            taxonomy = input.taxonomy,
        )
        if (analysisCache.contains(cacheKey)) {
            publishAnalysis(input.identity, analysisCache[cacheKey])
            return
        }
        val attempted = attemptedLocations.getOrPut(input.identity, ::linkedSetOf)
        if (
            mutableState.value.analysisStatus == ViewerOcrAnalysisStatus.NO_TEXT &&
            input.location !in attempted &&
            attempted.size >= MAX_NEGATIVE_LOCATIONS_PER_MEDIA
        ) {
            return
        }
        attempted += input.location
        analysisJob?.cancel()
        translationJob?.cancel()
        mutableState.value = ViewerOcrTranslationUiState(
            identity = input.identity,
            analysisStatus = ViewerOcrAnalysisStatus.RECOGNIZING,
        )
        analysisJob = scope.launch {
            val result = runCatchingPreservingCancellation {
                service.analyze(
                    ViewerOcrAnalysisRequest(
                        source = input.source,
                        location = input.location,
                        taxonomy = input.taxonomy,
                        enabledLanguages = configuration.enabledLanguages,
                        readyLanguages = configuration.readyLanguages,
                    ),
                )
            }
            if (activeInput != input || mutableState.value.identity != input.identity) return@launch
            result.onSuccess { analysis ->
                analysisCache[cacheKey] = analysis
                publishAnalysis(input.identity, analysis)
            }.onFailure {
                mutableState.value = ViewerOcrTranslationUiState(
                    identity = input.identity,
                    analysisStatus = ViewerOcrAnalysisStatus.FAILED,
                )
            }
        }
    }

    private fun publishAnalysis(identity: ViewerOcrSelectionIdentity, analysis: ViewerOcrAnalysis?) {
        if (mutableState.value.identity != identity) return
        mutableState.value = if (analysis == null) {
            ViewerOcrTranslationUiState(
                identity = identity,
                analysisStatus = ViewerOcrAnalysisStatus.NO_TEXT,
            )
        } else {
            ViewerOcrTranslationUiState(
                identity = identity,
                analysisStatus = ViewerOcrAnalysisStatus.DETECTED,
                language = analysis.language,
                regions = analysis.regions,
                imageWidth = analysis.imageWidth,
                imageHeight = analysis.imageHeight,
            )
        }
    }

    private fun publishCard(
        identity: ViewerOcrSelectionIdentity,
        card: ViewerTranslationCardState,
    ) {
        mutableState.update { current ->
            if (
                current.identity == identity &&
                current.regions.any { region -> region.id == card.regionId }
            ) {
                current.copy(translationCard = card)
            } else {
                current
            }
        }
    }

    private fun scheduleCardDismiss(
        identity: ViewerOcrSelectionIdentity,
        regionId: String,
        delayMs: Long,
    ) {
        translationJob?.cancel()
        translationJob = scope.launch {
            delay(delayMs)
            dismissCard(identity, regionId)
        }
    }

    private fun dismissCard(identity: ViewerOcrSelectionIdentity, regionId: String) {
        mutableState.update { current ->
            if (current.identity == identity && current.translationCard?.regionId == regionId) {
                current.copy(translationCard = null)
            } else {
                current
            }
        }
    }

    private fun isEnabled(identity: ViewerOcrSelectionIdentity?): Boolean {
        return identity != null &&
            configuration.automaticDetectionEnabled &&
            configuration.eligibleLanguages.isNotEmpty()
    }

    private fun cancelCurrentWork() {
        analysisJob?.cancel()
        analysisJob = null
        translationJob?.cancel()
        translationJob = null
    }

    override fun onCleared() {
        cancelCurrentWork()
        super.onCleared()
    }

    companion object {
        fun factory(service: ViewerOcrTranslationService): ViewModelProvider.Factory = viewModelFactory {
            initializer { ViewerOcrTranslationViewModel(service) }
        }
    }
}

private data class ViewerOcrAnalysisCacheKey(
    val identity: ViewerOcrSelectionIdentity,
    val location: String,
    val eligibleLanguages: Set<ViewerOcrLanguage>,
    val taxonomy: List<PostTaxonomyTerm>,
)

private data class ViewerTranslationCacheKey(
    val language: ViewerOcrLanguage,
    val sourceText: String,
)

private class BoundedAccessCache<K, V>(
    private val maximumEntries: Int,
) {
    private val values = LinkedHashMap<K, V>(maximumEntries, CACHE_LOAD_FACTOR, true)

    operator fun get(key: K): V? = values[key]

    operator fun set(key: K, value: V) {
        values[key] = value
        while (values.size > maximumEntries) values.remove(values.keys.first())
    }

    fun contains(key: K): Boolean = values.containsKey(key)
}

private const val MAX_ANALYSIS_CACHE_ENTRIES = 12
private const val MAX_TRANSLATION_CACHE_ENTRIES = 32
private const val MAX_NEGATIVE_LOCATIONS_PER_MEDIA = 2
private const val CACHE_LOAD_FACTOR = 0.75f
internal const val TRANSLATION_SUCCESS_DURATION_MS = 4_000L
internal const val TRANSLATION_FAILURE_DURATION_MS = 3_000L
