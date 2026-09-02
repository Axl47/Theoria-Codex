package com.theoriacodex.app.viewer.ocr

import com.theoriacodex.app.viewer.ViewerOcrPoint
import com.theoriacodex.app.viewer.ViewerOcrPolygon
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerOcrTranslationViewModelTest {
    @Test
    fun `disabled or unready configuration schedules no analysis then reconciles once ready`() = runTest {
        val service = FakeViewerOcrTranslationService()
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("one")
        val input = imageReady(identity)

        owner.synchronize(identity, configuration(enabled = false, ready = allLanguages))
        owner.onImageReady(input)
        owner.synchronize(identity, configuration(enabled = true, ready = emptySet()))
        runCurrent()
        assertTrue(service.analysisRequests.isEmpty())

        owner.synchronize(identity, configuration(enabled = true, ready = allLanguages))
        runCurrent()

        assertEquals(1, service.analysisRequests.size)
        assertEquals(ViewerOcrAnalysisStatus.NO_TEXT, owner.state.value.analysisStatus)
    }

    @Test
    fun `late analysis from replaced media cannot publish`() = runTest {
        val pending = CompletableDeferred<ViewerOcrAnalysis?>()
        val service = FakeViewerOcrTranslationService().apply { nextAnalysis = pending }
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val first = identity("first")
        val second = identity("second")
        owner.synchronize(first, configuration())
        owner.onImageReady(imageReady(first))
        runCurrent()
        assertEquals(ViewerOcrAnalysisStatus.RECOGNIZING, owner.state.value.analysisStatus)

        owner.synchronize(second, configuration())
        service.nextAnalysis = CompletableDeferred(completedAnalysis("second-region"))
        owner.onImageReady(imageReady(second))
        pending.complete(completedAnalysis("stale-region"))
        runCurrent()

        assertEquals(second, owner.state.value.identity)
        assertEquals(listOf("second-region"), owner.state.value.regions.map(ViewerOcrRegion::id))
    }

    @Test
    fun `positive analysis remains stable across a quality URL upgrade`() = runTest {
        val service = FakeViewerOcrTranslationService().apply {
            nextAnalysis = CompletableDeferred(completedAnalysis("visible"))
        }
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("stable")
        owner.synchronize(identity, configuration())
        owner.onImageReady(imageReady(identity, "https://example.test/sample.jpg"))
        runCurrent()

        owner.onImageReady(imageReady(identity, "https://example.test/original.jpg"))
        runCurrent()

        assertEquals(1, service.analysisRequests.size)
        assertEquals(listOf("visible"), owner.state.value.regions.map(ViewerOcrRegion::id))
    }

    @Test
    fun `negative analysis retries only one quality upgrade`() = runTest {
        val service = FakeViewerOcrTranslationService()
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("negative")
        owner.synchronize(identity, configuration())

        owner.onImageReady(imageReady(identity, "https://example.test/preview.jpg"))
        runCurrent()
        owner.onImageReady(imageReady(identity, "https://example.test/sample.jpg"))
        runCurrent()
        owner.onImageReady(imageReady(identity, "https://example.test/original.jpg"))
        runCurrent()

        assertEquals(2, service.analysisRequests.size)
        assertEquals(ViewerOcrAnalysisStatus.NO_TEXT, owner.state.value.analysisStatus)
    }

    @Test
    fun `disabling feature clears regions and cancels presentation`() = runTest {
        val service = FakeViewerOcrTranslationService().apply {
            nextAnalysis = CompletableDeferred(completedAnalysis("visible"))
        }
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("clear")
        owner.synchronize(identity, configuration())
        owner.onImageReady(imageReady(identity))
        runCurrent()
        assertTrue(owner.state.value.regions.isNotEmpty())

        owner.synchronize(identity, configuration(enabled = false))

        assertTrue(owner.state.value.regions.isEmpty())
        assertEquals(ViewerOcrAnalysisStatus.IDLE, owner.state.value.analysisStatus)
    }

    @Test
    fun `region tap moves through loading translation and timed success`() = runTest {
        val stageGate = CompletableDeferred<Unit>()
        val translation = CompletableDeferred<String>()
        val service = FakeViewerOcrTranslationService().apply {
            nextAnalysis = CompletableDeferred(completedAnalysis("phrase"))
            translationStageGate = stageGate
            nextTranslation = translation
        }
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("translate")
        owner.synchronize(identity, configuration())
        owner.onImageReady(imageReady(identity))
        runCurrent()

        owner.onRegionTapped("phrase")
        runCurrent()
        assertTrue(owner.state.value.translationCard is ViewerTranslationCardState.PreparingTranslator)

        stageGate.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.translationCard is ViewerTranslationCardState.Translating)

        translation.complete("Japanese text")
        runCurrent()
        assertEquals(
            ViewerTranslationCardState.Ready("phrase", "Japanese text"),
            owner.state.value.translationCard,
        )
        advanceTimeBy(TRANSLATION_SUCCESS_DURATION_MS - 1)
        runCurrent()
        assertTrue(owner.state.value.translationCard is ViewerTranslationCardState.Ready)
        advanceTimeBy(1)
        runCurrent()
        assertNull(owner.state.value.translationCard)

        owner.onRegionTapped("phrase")
        runCurrent()
        assertTrue(owner.state.value.translationCard is ViewerTranslationCardState.Ready)
        assertEquals(1, service.translationCallCount)
    }

    @Test
    fun `translation failure is bounded and retryable`() = runTest {
        val service = FakeViewerOcrTranslationService().apply {
            nextAnalysis = CompletableDeferred(completedAnalysis("phrase"))
            translationFailure = IllegalStateException("Translation unavailable")
        }
        val owner = ViewerOcrTranslationViewModel(service, backgroundScope)
        val identity = identity("failure")
        owner.synchronize(identity, configuration())
        owner.onImageReady(imageReady(identity))
        runCurrent()

        owner.onRegionTapped("phrase")
        runCurrent()
        assertEquals(
            ViewerTranslationCardState.Failed("phrase", "Translation unavailable"),
            owner.state.value.translationCard,
        )
        advanceTimeBy(TRANSLATION_FAILURE_DURATION_MS)
        runCurrent()
        assertNull(owner.state.value.translationCard)

        owner.onRegionTapped("phrase")
        runCurrent()
        assertEquals(2, service.translationCallCount)
    }

    private fun identity(name: String): ViewerOcrSelectionIdentity {
        return ViewerOcrSelectionIdentity(
            session = ViewerSessionIdentity("session-$name"),
            mediaKey = ViewerMediaKey(PostId(SourceKey.PIXIV, name), mediaIndex = 0),
            loadGeneration = 0,
        )
    }

    private fun imageReady(
        identity: ViewerOcrSelectionIdentity,
        location: String = "https://example.test/image.jpg",
    ) = ViewerOcrImageReady(
        identity = identity,
        source = SourceKey.PIXIV,
        location = location,
        taxonomy = emptyList(),
    )

    private fun configuration(
        enabled: Boolean = true,
        ready: Set<ViewerOcrLanguage> = allLanguages,
    ) = ViewerOcrConfiguration(
        automaticDetectionEnabled = enabled,
        enabledLanguages = allLanguages,
        readyLanguages = ready,
    )

    private fun completedAnalysis(regionId: String): ViewerOcrAnalysis {
        return ViewerOcrAnalysis(
            language = ViewerOcrLanguage.JAPANESE,
            regions = listOf(region(regionId)),
            imageWidth = 100,
            imageHeight = 200,
        )
    }

    private fun region(id: String) = ViewerOcrRegion(
        id = id,
        sourceText = "日本語",
        language = ViewerOcrLanguage.JAPANESE,
        polygon = ViewerOcrPolygon(
            listOf(
                ViewerOcrPoint(0.1f, 0.1f),
                ViewerOcrPoint(0.4f, 0.1f),
                ViewerOcrPoint(0.4f, 0.3f),
                ViewerOcrPoint(0.1f, 0.3f),
            ),
        ),
    )

    companion object {
        private val allLanguages = ViewerOcrLanguage.entries.toSet()
    }
}

private class FakeViewerOcrTranslationService : ViewerOcrTranslationService {
    val analysisRequests = mutableListOf<ViewerOcrAnalysisRequest>()
    var nextAnalysis = CompletableDeferred<ViewerOcrAnalysis?>().apply { complete(null) }
    var translationStageGate: CompletableDeferred<Unit>? = null
    var nextTranslation = CompletableDeferred("Translated")
    var translationFailure: Exception? = null
    var translationCallCount = 0

    override suspend fun analyze(request: ViewerOcrAnalysisRequest): ViewerOcrAnalysis? {
        analysisRequests += request
        return nextAnalysis.await()
    }

    override suspend fun translate(
        language: ViewerOcrLanguage,
        text: String,
        onStage: (ViewerTranslationStage) -> Unit,
    ): String {
        translationCallCount += 1
        translationStageGate?.await()
        onStage(ViewerTranslationStage.TRANSLATING)
        translationFailure?.let { throw it }
        return nextTranslation.await()
    }
}
