package com.theoriacodex.app.viewer.ocr

import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OcrLanguageModelManagerTest {
    @Test
    fun `refresh publishes independent downloaded states`() = runTest {
        val gateway = FakeOcrLanguageModuleGateway(
            downloaded = mutableSetOf(ViewerOcrLanguage.JAPANESE),
        )
        val manager = DefaultOcrLanguageModelManager(gateway)

        manager.refresh()

        assertEquals(OcrLanguageModelState.Ready, manager.states.value[ViewerOcrLanguage.JAPANESE])
        assertEquals(
            OcrLanguageModelState.NotDownloaded,
            manager.states.value[ViewerOcrLanguage.CHINESE],
        )
        assertEquals(
            OcrLanguageModelState.NotDownloaded,
            manager.states.value[ViewerOcrLanguage.KOREAN],
        )
    }

    @Test
    fun `download publishes progress and coalesces concurrent requests`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeOcrLanguageModuleGateway(downloadGate = gate)
        val manager = DefaultOcrLanguageModelManager(gateway)

        val first = async { manager.download(ViewerOcrLanguage.KOREAN) }
        runCurrent()
        val second = async { manager.download(ViewerOcrLanguage.KOREAN) }
        runCurrent()

        assertEquals(1, gateway.downloadCount[ViewerOcrLanguage.KOREAN])
        assertEquals(
            OcrLanguageModelState.Downloading(progressPercent = 40),
            manager.states.value[ViewerOcrLanguage.KOREAN],
        )

        gate.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, gateway.downloadCount[ViewerOcrLanguage.KOREAN])
        assertEquals(OcrLanguageModelState.Ready, manager.states.value[ViewerOcrLanguage.KOREAN])
    }

    @Test
    fun `unavailable module remains retry safe and does not become ready`() = runTest {
        val gateway = FakeOcrLanguageModuleGateway().apply {
            unavailable += ViewerOcrLanguage.CHINESE
        }
        val manager = DefaultOcrLanguageModelManager(gateway)

        assertFalse(manager.download(ViewerOcrLanguage.CHINESE))

        assertTrue(manager.states.value[ViewerOcrLanguage.CHINESE] is OcrLanguageModelState.Unavailable)
        assertEquals(1, gateway.downloadCount[ViewerOcrLanguage.CHINESE])
    }

}

private class FakeOcrLanguageModuleGateway(
    private val downloaded: MutableSet<ViewerOcrLanguage> = mutableSetOf(),
    private val downloadGate: CompletableDeferred<Unit>? = null,
) : OcrLanguageModuleGateway {
    val unavailable = mutableSetOf<ViewerOcrLanguage>()
    val downloadCount = mutableMapOf<ViewerOcrLanguage, Int>()

    override suspend fun isDownloaded(language: ViewerOcrLanguage): Boolean {
        if (language in unavailable) throw OcrModulesUnavailableException("Not supported")
        return language in downloaded
    }

    override suspend fun download(
        language: ViewerOcrLanguage,
        onProgress: (Int?) -> Unit,
    ) {
        downloadCount[language] = downloadCount.getOrDefault(language, 0) + 1
        if (language in unavailable) throw OcrModulesUnavailableException("Not supported")
        onProgress(40)
        downloadGate?.await()
        downloaded += language
    }
}
