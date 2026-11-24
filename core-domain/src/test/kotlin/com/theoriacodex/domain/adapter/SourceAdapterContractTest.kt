package com.theoriacodex.domain.adapter

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdapterContractTest {
    @Test
    fun `the base adapter inherits and shares nothing with a capability interface (ADR 0008)`() {
        val packageDirectory = Path.of(SourceAdapter::class.java.protectionDomain.codeSource.location.toURI())
            .resolve(SourceAdapter::class.java.packageName.replace('.', '/'))
        val capabilities = Files.list(packageDirectory).use { files ->
            files.map { it.name }.filter { it.endsWith("SourceAdapter.class") }.toList()
        }
            .map { file -> Class.forName("${SourceAdapter::class.java.packageName}.${file.removeSuffix(".class")}") }
            .filter { it.isInterface && it != SourceAdapter::class.java }
        fun Class<*>.memberNames() = declaredMethods.map { it.name.substringBefore('-') }.toSet()

        assertTrue(capabilities.isNotEmpty())
        assertEquals(emptyList<Class<*>>(), SourceAdapter::class.java.interfaces.toList())
        capabilities.forEach { capability ->
            assertEquals(capability.simpleName, emptySet<String>(), capability.memberNames() intersect SourceAdapter::class.java.memberNames())
        }
    }
}
