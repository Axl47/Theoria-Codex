package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFailureUiTextTest {
    @Test
    fun `auth failures point users to source account settings`() {
        val message = buildSourceAuthErrorMessage(
            listOf(
                SourceRunStatus(
                    source = SourceKey.PIXIV,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.AUTH_EXPIRED,
                ),
                SourceRunStatus(
                    source = SourceKey.RULE34XXX,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.AUTH_REQUIRED,
                ),
            ),
        )

        assertEquals(
            "Pixiv, R34X need account setup. Connect or refresh the account in Settings > Source Accounts.",
            message,
        )
    }

    @Test
    fun `non auth failures describe provider state without enum labels`() {
        val message = buildSourceFailureMessage(
            listOf(
                SourceRunStatus(
                    source = SourceKey.NHENTAI,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.NETWORK,
                    errorMessage = "",
                ),
                SourceRunStatus(
                    source = SourceKey.IWARA,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.RATE_LIMITED,
                    errorMessage = "HTTP 429",
                ),
                SourceRunStatus(
                    source = SourceKey.AIBOORU,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.PARSE,
                    errorMessage = null,
                ),
                SourceRunStatus(
                    source = SourceKey.GELBOORU,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.UNKNOWN,
                ),
            ),
        )

        assertEquals(
            """
            NHentai: Provider unreachable or blocked right now.
            Iwara: Rate limited. Wait a bit, then retry.
            AIBooru: Provider response changed; parsing needs an update.
            +1 more source errors
            """.trimIndent(),
            message,
        )
    }

    @Test
    fun `status chips use concise friendly labels`() {
        assertEquals(
            "Gelbooru needs account setup",
            sourceStatusChipText(
                SourceRunStatus(
                    source = SourceKey.GELBOORU,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.AUTH_REQUIRED,
                ),
            ),
        )
        assertEquals(
            "R34V unreachable",
            sourceStatusChipText(
                SourceRunStatus(
                    source = SourceKey.RULE34VIDEO,
                    state = SourceRunState.FAILED,
                    failureReason = SourceFailureReason.NETWORK,
                ),
            ),
        )
        assertEquals(
            "Pixiv excluded",
            sourceStatusChipText(SourceRunStatus(source = SourceKey.PIXIV, state = SourceRunState.EXCLUDED)),
        )
    }

    @Test
    fun `success and auth-only statuses do not produce failure banners`() {
        assertNull(
            buildSourceFailureMessage(
                listOf(
                    SourceRunStatus(source = SourceKey.PIXIV, state = SourceRunState.SUCCESS),
                    SourceRunStatus(
                        source = SourceKey.GELBOORU,
                        state = SourceRunState.FAILED,
                        failureReason = SourceFailureReason.AUTH_REQUIRED,
                    ),
                ),
            ),
        )
    }
}
