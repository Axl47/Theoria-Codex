package com.theoriacodex.app.developercopy

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.theoriacodex.app.sourceauth.AndroidCredentialEnvelopeStore
import com.theoriacodex.app.sourceauth.CredentialEnvelopeReadResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReadOnlyCredentialReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `export never repairs or deletes interrupted atomic credential artifacts`() = runTest {
        val directory = temporary.newFolder()
        val context = context(directory)
        val envelope = File(directory, "no_backup/test.json")
        envelope.parentFile!!.mkdirs()
        envelope.writeText("committed")
        File(envelope.path + ".bak").writeText("previous")
        File(envelope.path + ".new").writeText("unfinished")
        val before = envelope.parentFile!!.listFiles()!!.associate { it.name to it.readText() }
        val store = AndroidCredentialEnvelopeStore(context, relativeEnvelopePath = "test.json", allowAtomicRecovery = false)
        assertEquals(CredentialEnvelopeReadResult.TemporarilyUnavailable, store.read())
        assertEquals(before, envelope.parentFile!!.listFiles()!!.associate { it.name to it.readText() })
    }

    @Test fun `missing export credentials create no directories and committed malformed data remains untouched`() = runTest {
        val directory = temporary.newFolder()
        val context = context(directory)
        val store = AndroidCredentialEnvelopeStore(context, relativeEnvelopePath = "test.json", allowAtomicRecovery = false)
        assertEquals(CredentialEnvelopeReadResult.Missing, store.read())
        val noBackup = File(directory, "no_backup")
        assertFalse(noBackup.exists())
        noBackup.mkdirs()
        val envelope = File(noBackup, "test.json").apply { writeText("{\"formatVersion\":0}") }
        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, store.read())
        assertEquals("{\"formatVersion\":0}", envelope.readText())
        assertEquals(listOf("test.json"), noBackup.listFiles()!!.map { it.name })
    }

    @Test fun `ordinary credential reads still recover their committed backup`() = runTest {
        val directory = temporary.newFolder()
        val envelope = File(directory, "no_backup/test.json")
        envelope.parentFile!!.mkdirs()
        val backup = File(envelope.path + ".bak").apply { writeText("{\"formatVersion\":0}") }
        val store = AndroidCredentialEnvelopeStore(context(directory), relativeEnvelopePath = "test.json")
        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, store.read())
        assertEquals("{\"formatVersion\":0}", envelope.readText())
        assertFalse(backup.exists())
    }

    private fun context(directory: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(baseContext.applicationInfo).apply {
            dataDir = directory.path
        }
        override fun getNoBackupFilesDir(): File = File(directory, "no_backup")
    }
}
