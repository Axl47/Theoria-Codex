package com.theoriacodex.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import java.util.regex.PatternSyntaxException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegexCompatibilityDeviceTest {
    @Test
    fun everyStaticRegexCompilesOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val classNames = DexFile(context.packageCodePath).entries().toList().filter { it.startsWith("com.theoriacodex.") }
        assertTrue("no app classes found", classNames.isNotEmpty())
        val failures = classNames.mapNotNull { name ->
            val error = runCatching { Class.forName(name, true, context.classLoader) }.exceptionOrNull()
            generateSequence(error) { it.cause }.filterIsInstance<PatternSyntaxException>().firstOrNull()
                ?.let { "$name: ${it.description} in ${it.pattern}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
