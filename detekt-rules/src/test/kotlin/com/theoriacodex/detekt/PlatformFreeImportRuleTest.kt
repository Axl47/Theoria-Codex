package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformFreeImportRuleTest {
    private fun findings(imports: String): Int = PlatformFreeImportRule(Config.empty).lint("$imports\n\nval unused = 0\n").size

    @Test
    fun `an Android, Compose, lifecycle, Room, Media3, Coil or source adapter import is reported`() {
        val reported = listOf(
            "import android.os.Bundle",
            "import android.*",
            "import androidx.compose.runtime.State",
            "import androidx.lifecycle.ViewModel",
            "import androidx.room.Dao",
            "import androidx.media3.common.MediaItem",
            "import coil.ImageLoader",
            "import com.theoriacodex.sources.pixiv.PixivSourceAdapter as Pixiv",
        )

        assertEquals(reported.map { 1 }, reported.map { findings(it) })
    }

    @Test
    fun `domain, data, coroutine and other AndroidX imports are not reported`() {
        val silent = listOf(
            "import com.theoriacodex.domain.model.Post",
            "import com.theoriacodex.data.storage.DurableJson",
            "import kotlinx.coroutines.flow.Flow",
            "import androidx.annotation.VisibleForTesting",
            "import androidxtra.compose.Thing",
            "import com.theoriacodex.sourcesx.Thing",
        )

        assertEquals(silent.map { 0 }, silent.map { findings(it) })
    }

    @Test
    fun `the Theoria rule set runs the platform-free import rule`() {
        val rules = TheoriaRuleSetProvider().instance().rules.values.map { it(Config.empty) }

        assertEquals(1, rules.count { it is PlatformFreeImportRule })
    }
}
