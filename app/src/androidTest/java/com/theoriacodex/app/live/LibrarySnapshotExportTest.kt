package com.theoriacodex.app.live

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySnapshotExportTest {
    @Before
    fun requireExplicitOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT)?.toBooleanStrictOrNull() ?: false
        assumeTrue("Exporting the library snapshot requires $OPT_IN_ARGUMENT=true", enabled)
    }

    @Test
    fun exportLibrarySnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = context as TheoriaAppContainerOwner
        val backup = requireNotNull(owner.awaitAppContainer().workflows.profileBackup) { "Profile backup is unavailable" }
        val bytes = backup.export(includeRecents = true)
        val temporary = File(context.cacheDir, "$SNAPSHOT_FILE.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(File(context.cacheDir, SNAPSHOT_FILE)))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putLong("theoriaLibraryBytes", bytes.size.toLong()) })
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "theoriaExportLibrary"
        const val SNAPSHOT_FILE = "benchmark-library.json"
    }
}
